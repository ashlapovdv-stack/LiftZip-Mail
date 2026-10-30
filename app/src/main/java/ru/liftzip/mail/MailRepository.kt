package ru.liftzip.mail

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MailSnapshot(val accounts: List<MailAccount> = emptyList(), val messages: List<MailItem> = emptyList(), val history: Map<String,InboxHistory> = emptyMap(), val defaultAccountId: String? = null,
    val poll: PollSettings = PollSettings(), val historyDownload: HistoryDownload = HistoryDownload(), val checks: Map<String,SyncCheck> = emptyMap(),val background: BackgroundCheck=BackgroundCheck(),val bodyCache: BodyCacheStatus=BodyCacheStatus(),val autoHidePanels: Boolean=true)
data class SyncResult(val newUnread: Int, val errors: List<String>)

class MailApplication : Application() {
    val repository by lazy { MailRepository(this) }
    private val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    private val warmup=BodyWarmupSignals()
    @Volatile var visible=false
    override fun onCreate(){super.onCreate();scope.launchSafe{warmup.collect({visible},{repository.prefetchBodies(45_000)})};scope.launchSafe{
        repository.load()
        if(repository.snapshot.value.accounts.any{it.enabled})MailWorker.schedule(this@MailApplication)else MailWorker.cancel(this@MailApplication)
    }}
    fun warmBodies(){if(visible)warmup.request()}
    private fun kotlinx.coroutines.CoroutineScope.launchSafe(action: suspend ()->Unit)=launch {
        try{action()}catch(e: CancellationException){throw e}catch(_: Exception){/* Durable work retries separately. */}
    }
}

class MailRepository(private val context: Context) {
    private val db=LocalStore(context)
    private val vault=SecretVault()
    private val gateway=MailGateway(context)
    private val attachmentCache=AttachmentCache(context)
    private val forwarded=ForwardAttachments(context)
    private val notifications=MailNotifications(context)
    private val settings=context.getSharedPreferences("mail-settings",Context.MODE_PRIVATE)
    private val syncPrefs=context.getSharedPreferences("sync-baseline",Context.MODE_PRIVATE)
    private val coordinator=MailboxCoordinator()
    private val historyMutex=Mutex()
    private val bodyMutex=Mutex()
    private val bodyFailures=readBodyFailures()
    private var bodyCache=BodyCacheStatus(saved=settings.getInt("body-count",0),finished=settings.getLong("body-end",0),error=settings.getString("body-error","").orEmpty())
    private val parallelChecks=Semaphore(3)
    private val forwardTransfers=Semaphore(2)
    private val forwardLocks=ConcurrentHashMap<String,Mutex>()
    private val attachmentLocks=ConcurrentHashMap<String,Mutex>()
    private val forwardProgress=MutableStateFlow<Map<String,ForwardPlan>>(emptyMap())
    val forwardingProgress=forwardProgress.asStateFlow()
    private val flagVersions=mutableMapOf<String,Long>()
    private var nextFlagVersion=0L
    private val state=MutableStateFlow(MailSnapshot())
    val snapshot=state.asStateFlow()
    private fun refresh(arrived: Set<String> = emptySet(),messages: List<MailItem>?=null) {
        val accounts=db.accounts()
        val items=messages?:db.messages()
        state.value=MailSnapshot(accounts,items,accounts.associate { a->
            a.id to InboxHistory(if(syncPrefs.contains("count:${a.id}"))syncPrefs.getInt("count:${a.id}",0) else null,syncPrefs.getBoolean("older:${a.id}",true),
                if(syncPrefs.contains("unread:${a.id}"))syncPrefs.getInt("unread:${a.id}",0)else null)
        },settings.getString("default-sender",null)?.takeIf{id->accounts.any{it.id==id}},
            PollSettings.normalized(settings.getInt("foreground-minutes",1),settings.getInt("background-minutes",15)),readHistory(),
            accounts.associate{it.id to SyncCheck(syncPrefs.getLong("checked:${it.id}",0),syncPrefs.getString("error:${it.id}","").orEmpty(),syncPrefs.getLong("duration:${it.id}",0))},
            BackgroundCheck(settings.getLong("poll-start",0),settings.getLong("poll-end",0),settings.getString("poll-result","").orEmpty(),settings.getString("poll-error","").orEmpty(),settings.getInt("poll-arrived",0),
                settings.getString("poll-kind","auto").orEmpty(),settings.getLong("poll-auto-start",0),settings.getLong("poll-auto-end",0),settings.getString("poll-auto-result","").orEmpty()),
            bodyCache.copy(pending=ContentCacheRules.pending(items,accounts,notifications.contentPriorityIds()).size),settings.getBoolean("auto-hide-panels",true))
        notifications.reconcile(SyncRules.activeMessages(state.value.messages,accounts,null),arrived,accounts){id->db.message(id)?.body?.take(3000)}
        notifications.history(state.value)
    }
    suspend fun load()=withContext(Dispatchers.IO){coordinator.data{
        refresh()
        state.value.messages.filter{it.localFolder=="drafts" && it.forwarding?.active==true}.forEach{ForwardWorker.enqueue(context,it.id)}
    }}
    private data class Request(val account: MailAccount,val version: Long,val cached: List<MailItem>,val flags: Map<String,Long>,val cursor: ArrivalCursor)
    private data class CheckReply(val inbox: InboxPage?=null,val unread: UnreadPage?=null)
    private data class HistoryRequest(val request: Request,val missing: List<MailItem>)
    private data class HistoryReply(val page: InboxPage?=null)
    private fun capture(id: String): Request? {
        val a=state.value.accounts.find{it.id==id && it.enabled}?:return null
        val cached=state.value.messages.filter{it.accountId==id && SyncRules.remoteInbox(it)}
        val cursor=if(syncPrefs.contains("alert-validity:$id"))ArrivalCursor(syncPrefs.getLong("alert-validity:$id",0),syncPrefs.getLong("alert-uid:$id",0),syncPrefs.getBoolean("alert-ready:$id",false))
            else ArrivalCursor(cached.firstOrNull()?.validity?:syncPrefs.getLong("validity:$id",0),cached.maxOfOrNull{it.uid}?:0,syncPrefs.getBoolean(id,false))
        return Request(a,coordinator.version(id),cached,flagVersions.toMap(),cursor)
    }
    private fun current(request: Request)=state.value.accounts.any{it.id==request.account.id && it.enabled} && coordinator.version(request.account.id)==request.version
    private fun protected(request: Request)=flagVersions.keys.filter{(flagVersions[it]?:0)!=(request.flags[it]?:0)}.toSet()
    private fun flagsChanged(ids: Collection<String>){nextFlagVersion++;ids.forEach{flagVersions[it]=nextFlagVersion}}
    private fun rememberEpoch(id: String,validity: Long?,total: Int?) {
        if(validity==null)return
        val old=syncPrefs.getLong("validity:$id",state.value.messages.firstOrNull{it.accountId==id && SyncRules.remoteInbox(it)}?.validity?:0)
        if(old!=0L && old!=validity || total==0 && state.value.messages.any{it.accountId==id && SyncRules.remoteInbox(it)})coordinator.invalidate(id)
        syncPrefs.edit().putLong("validity:$id",validity).apply()
    }
    private fun saveRemote(id: String,previous: List<MailItem>,merged: List<MailItem>,arrived: Set<String> = emptySet()) {
        db.applyInbox(previous,merged)
        refresh(arrived,state.value.messages.filterNot{it.accountId==id && SyncRules.remoteInbox(it)}+merged.map(MailContent::preview))
    }
    /** Resolve a notification before routing, independently of the list's snapshot collector. */
    suspend fun cachedMessage(id: String): MailItem?=withContext(Dispatchers.IO){db.message(id)?.let{it.copy(previewOnly=!it.contentCached)}}
    suspend fun message(id: String): MailItem?=withContext(Dispatchers.IO){coordinator.content(id){
        val cached=db.message(id)?:return@content null
        if(cached.contentCached)return@content cached
        coordinator.request(prepare={capture(cached.accountId)?.let{request->request to (db.message(id)?:cached)}},fetch={(request,m)->
            val coroutine=currentCoroutineContext()
            if(m.contentCached)m else runInterruptible{gateway.content(request.account,vault.open(request.account.id,request.account.secret),m){coroutine.ensureActive()}}
        },commit={(request,_),full->
            require(current(request)){"Ящик приостановлен или его настройки изменились."}
            val now=state.value.messages.find{it.id==id}?:return@request null
            val result=full.copy(unread=now.unread,starred=now.starred,localFolder=now.localFolder)
            db.putMessage(result);refresh(messages=state.value.messages.map{if(it.id==id)MailContent.preview(result)else it});result
        })?:error("Включите ящик в меню, чтобы загрузить текст письма.")
    }}
    suspend fun search(query: String): Set<String> =withContext(Dispatchers.IO){
        val coroutine=currentCoroutineContext();db.search(query){coroutine.ensureActive()}
    }
    suspend fun addAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO){coordinator.data {
        require(db.accounts().none { it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true) }) { "Этот ящик уже добавлен." }
        gateway.verify(account,password)
        // Persist only after both incoming and outgoing authentication succeed.
        val encrypted=account.copy(secret=vault.seal(account.id,password))
        db.putAccount(encrypted)
        refresh()
    }}
    suspend fun editAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO){coordinator.data {
        val old=db.accounts().find{it.id==account.id}?:error("Ящик не найден.")
        require(db.accounts().none{it.id!=old.id && it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true)}){"Этот ящик уже добавлен."}
        require(old.email.equals(account.email,true)){"Для другого адреса добавьте отдельный ящик."}
        MailRules.validateAccount(account,password,passwordRequired=false)?.let{error(it)}
        val secret=password.ifEmpty{vault.open(old.id,old.secret)}
        gateway.verify(account,secret)
        val reset=!SyncRules.sameInbox(old,account)
        if(reset)syncPrefs.edit().remove(old.id).remove("count:${old.id}").remove("unread:${old.id}").remove("older:${old.id}").remove("alert-validity:${old.id}").remove("alert-uid:${old.id}").remove("alert-ready:${old.id}").apply()
        coordinator.invalidate(old.id)
        if(reset)attachmentCache.removeAccount(old.id)
        db.updateAccount(account.copy(secret=vault.seal(old.id,secret),enabled=old.enabled),reset)
        val history=readHistory()
        if(reset && old.id in history.accountIds && history.active)writeHistory(history.copy(status="paused",error="Настройки ящика изменены. Продолжите загрузку истории."))
        refresh()
    }}
    suspend fun removeAccount(id: String)=withContext(Dispatchers.IO){coordinator.data{
        db.messages().filter{it.accountId==id}.forEach{ForwardWorker.cancel(context,it.id);forwardProgress.update{progress->progress-it.id};forwarded.remove(it.id)}
        coordinator.invalidate(id);db.removeAccount(id);attachmentCache.removeAccount(id)
        if(settings.getString("default-sender",null)==id)settings.edit().remove("default-sender").apply()
        syncPrefs.edit().remove(id).remove("count:$id").remove("unread:$id").remove("older:$id").remove("checked:$id").remove("error:$id").apply()
        val job=readHistory()
        if(id in job.accountIds)writeHistory(job.copy(accountIds=job.accountIds-id,status=if(job.accountIds.size==1)"done" else job.status))
        refresh()
    }}
    suspend fun setAccountEnabled(id: String,enabled: Boolean)=withContext(Dispatchers.IO){coordinator.data {
        val account=state.value.accounts.find{it.id==id}?:return@data
        if(account.enabled==enabled)return@data
        coordinator.invalidate(id)
        db.updateAccount(account.copy(enabled=enabled),resetInbox=false)
        val job=readHistory()
        if(!enabled && job.active && id in job.accountIds && db.accounts().none{it.enabled && it.id in job.accountIds && SyncRules.needsHistory(it.id,state.value.messages,state.value.history[it.id])}) {
            writeHistory(job.copy(status="paused",error="Включите приостановленные ящики в меню и продолжите загрузку."))
        }
        refresh(messages=state.value.messages)
    }}
    suspend fun setDefaultAccount(id: String?)=withContext(Dispatchers.IO){coordinator.data{
        require(id==null || db.accounts().any{it.id==id}){"Ящик не найден."}
        settings.edit().putString("default-sender",id).apply();refresh()
    }}
    suspend fun setAutoHidePanels(enabled: Boolean)=withContext(Dispatchers.IO){coordinator.data{
        settings.edit().putBoolean("auto-hide-panels",enabled).apply();refresh(messages=state.value.messages)
    }}
    suspend fun setPoll(poll: PollSettings)=withContext(Dispatchers.IO){coordinator.data{
        val p=PollSettings.normalized(poll.foregroundMinutes,poll.backgroundMinutes)
        settings.edit().putInt("foreground-minutes",p.foregroundMinutes).putInt("background-minutes",p.backgroundMinutes).apply();refresh()
    }}
    suspend fun sync(accountId: String?=null,warmBodies: Boolean=true): SyncResult=checkAccounts(accountId,false).also{if(warmBodies)requestBodyWarmup()}
    suspend fun syncUnread(accountId: String?): SyncResult=checkAccounts(accountId,true).also{requestBodyWarmup()}
    private suspend fun checkAccounts(accountId: String?,unread: Boolean): SyncResult=supervisorScope {
        val ids=coordinator.data{state.value.accounts.filter{it.enabled && (accountId==null || it.id==accountId)}.map{it.id}}
        val results=ids.map{id->async(Dispatchers.IO){parallelChecks.withPermit{checkAccount(id,unread)}}}.awaitAll()
        SyncResult(results.sumOf{it.newUnread},results.flatMap{it.errors})
    }
    private suspend fun checkAccount(id: String,unread: Boolean): SyncResult=coordinator.poll(id) {
        var request: Request?=null
        val started=System.nanoTime()
        try {
            coordinator.request(prepare={capture(id).also{request=it}},fetch={captured->
                withTimeout(30_000){val coroutine=currentCoroutineContext();runInterruptible(Dispatchers.IO){
                    if(unread)CheckReply(unread=gateway.unread(captured.account,vault.open(id,captured.account.secret),captured.cached){coroutine.ensureActive()})
                    else CheckReply(inbox=gateway.inbox(captured.account,vault.open(id,captured.account.secret),captured.cached,checkCancelled={coroutine.ensureActive()}))
                }}
            },commit={captured,response->
                if(!current(captured))return@request SyncResult(0,emptyList())
                val now=state.value.messages.filter{it.accountId==id && SyncRules.remoteInbox(it)}
                val guarded=protected(captured)
                val page=response.inbox
                val unreadPage=response.unread
                val merged=if(page!=null)SyncRules.checkedPage(captured.cached,now,page,guarded) else SyncRules.unreadPage(captured.cached,now,unreadPage!!,guarded)
                val validity=page?.validity?:unreadPage!!.validity
                val observedIds=(page?.messages?:unreadPage!!.messages).map{it.id}.toSet()
                val arrival=NotificationRules.arrivals(captured.cursor,validity?:0,merged.filter{it.id in observedIds},complete=page!=null)
                val arrived=arrival.arrived
                val total=page?.history?.total?:unreadPage?.total
                val previousEpoch=now.firstOrNull()?.validity
                rememberEpoch(id,validity,total)
                val edit=syncPrefs.edit().putInt("count:$id",total?:merged.size).putLong("checked:$id",System.currentTimeMillis())
                    .putLong("duration:$id",(System.nanoTime()-started)/1_000_000).remove("error:$id")
                val unreadCount=page?.history?.unread?:unreadPage?.messages?.count{it.unread}
                if(unreadCount!=null)edit.putInt("unread:$id",UnreadCounts.reconcile(unreadCount,page?.messages?:unreadPage!!.messages,now,guarded))
                else if(previousEpoch!=null && previousEpoch!=validity)edit.remove("unread:$id")
                if(page!=null) {
                    val oldIds=captured.cached.filter{it.localFolder=="inbox"}.map{it.id}.toSet()
                    val expanded=now.any{it.localFolder=="inbox" && it.id !in oldIds}
                    edit.putBoolean(id,true).putBoolean("older:$id",if(expanded && previousEpoch==validity)state.value.history[id]?.hasOlder?:page.history.hasOlder else page.history.hasOlder)
                }else if(previousEpoch!=null && previousEpoch!=validity)edit.putBoolean(id,false).putBoolean("older:$id",true)
                edit.apply();saveRemote(id,now,merged,arrived)
                // Commit the cursor only after the mail and pending notification were saved.
                // An interrupted save must leave this UID eligible for a later retry.
                syncPrefs.edit().putLong("alert-validity:$id",arrival.cursor.validity).putLong("alert-uid:$id",arrival.cursor.uid).putBoolean("alert-ready:$id",arrival.cursor.ready).apply()
                SyncResult(arrived.size,emptyList())
            })?:SyncResult(0,emptyList())
        }catch(e: TimeoutCancellationException){checkFailed(request,SocketTimeoutException(),started)}
        catch(e: CancellationException){throw e}catch(e: Exception){checkFailed(request,e,started)}
    }
    private suspend fun checkFailed(request: Request?,error: Exception,started: Long): SyncResult=coordinator.data {
        if(request==null || !current(request))return@data SyncResult(0,emptyList())
        val message=friendlyError(error);val id=request.account.id
        syncPrefs.edit().putString("error:$id",message).putLong("duration:$id",(System.nanoTime()-started)/1_000_000).apply()
        refresh(messages=state.value.messages);SyncResult(0,listOf("${request.account.name}: $message"))
    }
    suspend fun loadOlder(accountId: String?): SyncResult {
        val ids=coordinator.data{state.value.accounts.filter{it.enabled && (accountId==null || it.id==accountId)}.map{it.id}}
        val generation=startHistory(ids);HistoryWorker.enqueue(context,generation);return SyncResult(0,emptyList())
    }
    suspend fun changeFlags(message: MailItem,unread: Boolean?=null,starred: Boolean?=null)=withContext(Dispatchers.IO) {
        val request=coordinator.data{capture(message.accountId)}?:error("Ящик приостановлен. Включите его в меню.")
        if(message.uid>0)runInterruptible(Dispatchers.IO){gateway.flags(request.account,vault.open(request.account.id,request.account.secret),message,seen=unread?.not(),starred=starred)}
        coordinator.data {
            require(current(request)){"Ящик приостановлен или его настройки изменились."}
            val now=state.value.messages.find{it.id==message.id}?:return@data
            val updated=now.copy(unread=unread?:now.unread,starred=starred?:now.starred)
            adjustUnreadCount(now.accountId,listOf(now),listOf(updated))
            flagsChanged(listOf(now.id));db.putMessage(updated);refresh(messages=state.value.messages.map{if(it.id==now.id)updated else it})
        }
    }
    suspend fun markRead(ids: Set<String>,unread: Boolean): FlagChange=withContext(Dispatchers.IO) {
        val groups=coordinator.data{state.value.messages.filter{it.id in ids}.groupBy{Triple(it.accountId,it.remoteFolder,it.validity)}}
        val changed=mutableSetOf<String>();val errors=mutableListOf<String>()
        for((key,group) in groups) {
            val request=coordinator.data{capture(key.first)}
            if(request==null){errors+="Ящик приостановлен. Включите его в меню.";continue}
            try {
                val remote=group.filter{it.uid>0}
                val uids=if(remote.isEmpty())emptySet() else runInterruptible(Dispatchers.IO){gateway.flagsMany(request.account,vault.open(request.account.id,request.account.secret),remote,seen=!unread)}
                coordinator.data {
                    if(!current(request))return@data
                    val successful=group.filter{it.uid<=0 || it.uid in uids}.map{it.id}.toSet()
                    val updated=state.value.messages.filter{it.id in successful}.map{it.copy(unread=unread)}
                    adjustUnreadCount(key.first,state.value.messages.filter{it.id in successful},updated)
                    db.putMessages(updated);flagsChanged(successful);changed+=successful
                    refresh(messages=state.value.messages.map{if(it.id in successful)it.copy(unread=unread)else it})
                    if(successful.size!=group.size)errors+="${request.account.name}: некоторые письма перемещены или удалены. Обновите список."
                }
            }catch(e: CancellationException){throw e}catch(e: Exception){errors+="${request.account.name}: ${friendlyError(e)}"}
        }
        FlagChange(changed,errors.distinct())
    }
    suspend fun deleteMessages(ids: Set<String>): DeleteResult=withContext(Dispatchers.IO) {
        val chosen=coordinator.data{state.value.messages.filter{it.id in ids}}
        val removed=mutableSetOf<String>();val errors=mutableListOf<String>()
        coordinator.data {
            val local=chosen.filter(TrashRules::local).map{it.id}.toSet()
            removeCached(local);removed+=local
        }
        if(chosen.any{!TrashRules.remote(it) && !TrashRules.local(it)})errors+="Некоторые письма сейчас нельзя удалить. Дождитесь окончания отправки и повторите."
        val groups=chosen.filter(TrashRules::remote).groupBy{Triple(it.accountId,it.remoteFolder,it.validity)}
        for((key,group) in groups)coordinator.poll(key.first) {
            var request=coordinator.data{capture(key.first)}
            if(request==null){errors+="Включите ящик в боковом меню, чтобы переместить письма в Корзину.";return@poll}
            try {
                val a=request!!.account
                gateway.trashMany(a,vault.open(a.id,a.secret),group,before={coordinator.data{val valid=current(request!!);if(!valid)errors+="${a.name}: настройки ящика изменились; обновите список.";valid}},onConfirmed={uids->coordinator.data {
                    if(!current(request!!)){errors+="${a.name}: настройки ящика изменились; обновите список.";return@data false}
                    val successful=group.filter{it.uid in uids}.map{it.id}.toSet()
                    removeCached(successful);removed+=successful
                    // Reject all polls/history/content captured before this acknowledged move.
                    coordinator.invalidate(a.id);request=request!!.copy(version=coordinator.version(a.id))
                    true
                }})
            }catch(e: CancellationException){throw e}catch(e: Exception){errors+="${request!!.account.name}: ${friendlyError(e)}"}
        }
        DeleteResult(removed,errors.distinct())
    }
    /** Called under the short data lock, after server acknowledgement or for local-only mail. */
    private fun removeCached(ids: Set<String>) {
        if(ids.isEmpty())return
        val rows=state.value.messages.filter{it.id in ids}
        db.removeMessages(ids)
        rows.filter{it.localFolder=="drafts"}.forEach{ForwardWorker.cancel(context,it.id);forwarded.remove(it.id)}
        forwardProgress.update{it.filterKeys{key->key !in ids}}
        rows.groupBy{it.accountId}.forEach{(account,items)->
            val remote=items.filter(TrashRules::remote)
            if(remote.isNotEmpty()) {
                val edit=syncPrefs.edit()
                if(syncPrefs.contains("count:$account"))edit.putInt("count:$account",(syncPrefs.getInt("count:$account",0)-remote.size).coerceAtLeast(0))
                if(syncPrefs.contains("unread:$account"))edit.putInt("unread:$account",TrashRules.unreadAfter(syncPrefs.getInt("unread:$account",0),remote))
                edit.apply()
            }
        }
        val job=readHistory();if(job.issues.any{it.messageId in ids})writeHistory(job.copy(issues=job.issues.filter{it.messageId !in ids}))
        refresh(messages=state.value.messages.filter{it.id !in ids});saveBodyFailures()
    }
    private fun adjustUnreadCount(id: String,before: List<MailItem>,after: List<MailItem>) {
        if(syncPrefs.contains("unread:$id"))syncPrefs.edit().putInt("unread:$id",UnreadCounts.changed(syncPrefs.getInt("unread:$id",0),before,after)).apply()
    }
    private fun readHistory(): HistoryDownload=runCatching{
        val j=JSONObject(settings.getString("history-download","{}").orEmpty())
        val ids=j.optJSONArray("accounts")?:JSONArray()
        val issues=j.optJSONArray("issues")?:JSONArray()
        HistoryDownload(j.optString("id"),List(ids.length()){ids.getString(it)},j.optString("status","idle"),j.optString("error"),
            List(issues.length()){i->val issue=issues.getJSONObject(i);HistoryIssue(issue.getString("id"),issue.getString("account"),issue.optString("detail"),issue.optInt("attempts",1))},
            j.optLong("activityAt"),j.optLong("savedAt"),j.optString("phase"),j.optString("currentAccount"),j.optLong("currentUid"))
    }.getOrDefault(HistoryDownload())
    private fun writeHistory(job: HistoryDownload) {
        val issues=JSONArray().apply{job.issues.forEach{put(JSONObject().put("id",it.messageId).put("account",it.accountId).put("detail",it.detail).put("attempts",it.attempts))}}
        settings.edit().putString("history-download",JSONObject().put("id",job.id).put("accounts",JSONArray(job.accountIds))
            .put("status",job.status).put("error",job.error).put("issues",issues).put("activityAt",job.activityAt).put("savedAt",job.savedAt)
            .put("phase",job.phase).put("currentAccount",job.currentAccountId).put("currentUid",job.currentUid).toString()).apply()
    }
    suspend fun startHistory(ids: List<String>): String=withContext(Dispatchers.IO){coordinator.data{
        val available=db.accounts().filter{it.enabled}.map{it.id}.toSet()
        val selected=ids.distinct().filter{it in available}
        require(selected.isNotEmpty()){"Выберите хотя бы один ящик."}
        val job=HistoryDownload(UUID.randomUUID().toString(),selected,"waiting",activityAt=System.currentTimeMillis())
        writeHistory(job);refresh();job.id
    }}
    suspend fun pauseHistory()=withContext(Dispatchers.IO){coordinator.data{
        val job=readHistory();if(job.active){writeHistory(job.copy(status="paused"));refresh()}
    }}
    suspend fun waitHistory(id: String)=withContext(Dispatchers.IO){coordinator.data{
        val job=readHistory();if(job.id==id && job.active){writeHistory(job.copy(status="waiting"));refresh()}
    }}
    suspend fun historyStep(id: String,skipped: Set<String>): HistoryStep=historyMutex.withLock {
        var captured: HistoryRequest?=null
        var stopped=HistoryStep()
        try {
            coordinator.request(prepare={
                val job=readHistory()
                if(job.id!=id || !job.active)return@request null
                val accounts=state.value.accounts
                val issues=HistoryRules.remainingIssues(job,state.value.messages)
                val history=accounts.associate{it.id to InboxHistory(hasOlder=state.value.history[it.id]?.hasOlder!=false || HistoryRules.pendingBodies(it.id,state.value.messages,issues).isNotEmpty())}
                val accountId=SyncRules.nextHistoryAccount(job,accounts,history,skipped)
                if(accountId==null) {
                    val retry=SyncRules.nextHistoryAccount(job,accounts,history,emptySet())!=null
                    val disabled=accounts.any{!it.enabled && it.id in job.accountIds && history[it.id]?.hasOlder==true}
                    writeHistory(job.copy(status=if(retry)"waiting" else if(disabled)"paused" else if(issues.isNotEmpty())"partial" else "done",issues=issues,phase="",currentUid=0,error=if(retry)job.error else if(disabled)"Включите приостановленные ящики в меню и продолжите загрузку." else ""))
                    refresh(messages=state.value.messages);stopped=HistoryStep(retry=retry);null
                }else capture(accountId)?.let{request->
                    val missing=if(state.value.history[accountId]?.hasOlder!=false)emptyList() else HistoryRules.pendingBodies(accountId,request.cached,issues).take(10)
                    writeHistory(job.copy(status="running",issues=issues,activityAt=System.currentTimeMillis(),phase=if(missing.isEmpty())"headers" else "bodies",currentAccountId=accountId,currentUid=0))
                    refresh(messages=state.value.messages)
                    HistoryRequest(request,missing).also{captured=it}
                }
            },fetch={work->
                val coroutine=currentCoroutineContext();val a=work.request.account
                if(work.missing.isEmpty()) {
                    runInterruptible(Dispatchers.IO){HistoryReply(page=gateway.inbox(a,vault.open(a.id,a.secret),work.request.cached,loadOlder=true,pageSize=50,checkCancelled={coroutine.ensureActive()}))}
                }else {
                    gateway.contents(a,vault.open(a.id,a.secret),work.missing,onItem={batch->
                        coordinator.data {
                            val job=readHistory()
                            if(!current(work.request) || job.id!=id || !job.active)return@data false
                            val now=state.value.messages.filter{it.accountId==a.id && SyncRules.remoteInbox(it)}
                            val merged=SyncRules.contentBatch(now,batch)
                            writeHistory(HistoryRules.afterBody(job,now,batch,System.currentTimeMillis()))
                            if(batch.missingIds.isNotEmpty())coordinator.invalidate(a.id)
                            saveRemote(a.id,now,merged)
                            batch.missingIds.isEmpty()
                        }
                    },onStart={message->
                        coordinator.data {
                            val job=readHistory()
                            if(!current(work.request) || job.id!=id || !job.active)return@data false
                            writeHistory(job.copy(activityAt=System.currentTimeMillis(),currentUid=message.uid))
                            refresh(messages=state.value.messages);true
                        }
                    },around={m,action->coordinator.content(m.id){if(coordinator.data{db.message(m.id)?.contentCached==true})true else action()}})
                    HistoryReply()
                }
            },commit={work,response->
                val job=readHistory();val request=work.request;val a=request.account
                if(!current(request) || job.id!=id || !job.active)return@request HistoryStep(more=true)
                val now=state.value.messages.filter{it.accountId==a.id && SyncRules.remoteInbox(it)}
                val page=response.page
                if(page==null)return@request HistoryStep(more=true)
                val merged=SyncRules.historyPage(now,page,protected(request))
                rememberEpoch(a.id,page.validity,page.history.total)
                syncPrefs.edit().putInt("count:${a.id}",page.history.total?:merged.size).putBoolean("older:${a.id}",page.history.hasOlder).apply()
                writeHistory(job.copy(status="running",activityAt=System.currentTimeMillis(),savedAt=System.currentTimeMillis(),error=if(skipped.isEmpty())"" else job.error))
                saveRemote(a.id,now,merged);HistoryStep(more=true)
            })?:stopped
        }catch(e: CancellationException){throw e}catch(e: Exception){
            coordinator.data {
                val work=captured;val job=readHistory()
                if(work!=null && current(work.request) && job.id==id && job.active){writeHistory(job.copy(error="${work.request.account.name}: ${friendlyError(e)}"));refresh(messages=state.value.messages)}
            }
            HistoryStep(more=true,failedAccount=captured?.request?.account?.id)
        }
    }
    suspend fun notificationEnvironmentChanged()=withContext(Dispatchers.IO){coordinator.data{
        refresh(messages=state.value.messages)
        if(ContentCacheRules.pending(state.value.messages,state.value.accounts,notifications.contentPriorityIds()).isNotEmpty()){
            NotificationPreviewWorker.enqueue(context);(context.applicationContext as MailApplication).warmBodies()
        }
    }}
    suspend fun dismissNotifications(ids: Set<String>)=withContext(Dispatchers.IO){coordinator.data{notifications.dismiss(ids);refresh(messages=state.value.messages)}}
    suspend fun testNotification(): Boolean=withContext(Dispatchers.IO){notifications.test()}
    suspend fun backgroundStarted(token: String,kind: String="auto")=withContext(Dispatchers.IO){coordinator.data{
        val now=System.currentTimeMillis()
        settings.edit().putString("poll-token",token).putString("poll-kind",kind).putLong("poll-start",now).putLong("poll-end",0).putInt("poll-arrived",0).putString("poll-result","running").putString("poll-error","")
            .apply{if(kind=="auto")putString("poll-auto-token",token).putLong("poll-auto-start",now).putLong("poll-auto-end",0).putString("poll-auto-result","running")}.apply()
        refresh(messages=state.value.messages)
    }}
    suspend fun backgroundFinished(token: String,result: String,error: String="",newUnread: Int=0)=withContext(Dispatchers.IO){coordinator.data{
        if(settings.getString("poll-auto-token",null)==token)settings.edit().putLong("poll-auto-end",System.currentTimeMillis()).putString("poll-auto-result",result).apply()
        if(settings.getString("poll-token",null)==token)settings.edit().putLong("poll-end",System.currentTimeMillis()).putString("poll-result",result).putString("poll-error",error).putInt("poll-arrived",newUnread).apply()
        refresh(messages=state.value.messages)
    }}
    private fun readBodyFailures(): MutableMap<String,BodyFailure> = runCatching {
        val entries=JSONArray(settings.getString("body-failures","[]"))
        (0 until entries.length()).associate{i->val j=entries.getJSONObject(i);j.getString("id") to BodyFailure(j.getInt("attempts"),j.getLong("retryAt"),j.getString("error"))}.toMutableMap()
    }.getOrDefault(mutableMapOf())
    private fun saveBodyFailures(){
        val missing=state.value.messages.filter{!it.contentCached}.map{it.id}.toSet()
        bodyFailures.keys.retainAll(missing)
        val entries=JSONArray().apply{bodyFailures.entries.sortedByDescending{it.value.retryAt}.take(200).forEach{(id,f)->
            put(JSONObject().put("id",id).put("attempts",f.attempts).put("retryAt",f.retryAt).put("error",f.error))
        }}
        settings.edit().putString("body-failures",entries.toString()).apply()
    }
    suspend fun requestBodyWarmup()=withContext(Dispatchers.IO){
        val pending=coordinator.data{ContentCacheRules.pending(state.value.messages,state.value.accounts,notifications.contentPriorityIds()).isNotEmpty()}
        if(pending){NotificationPreviewWorker.enqueue(context);(context.applicationContext as MailApplication).warmBodies()}
    }
    suspend fun retryBodyWarmup()=withContext(Dispatchers.IO){
        coordinator.data{bodyFailures.clear();saveBodyFailures();bodyCache=bodyCache.copy(error="");refresh(messages=state.value.messages)}
        requestBodyWarmup()
    }
    /** Same pipeline is used in the polling worker and while visible; only one pipeline owns a batch. */
    suspend fun prefetchBodies(budgetMs: Long=120_000): BodyWarmupResult=withContext(Dispatchers.IO){bodyMutex.withLock{
        if(coordinator.data{ContentCacheRules.pending(state.value.messages,state.value.accounts,notifications.contentPriorityIds()).isEmpty()})return@withLock BodyWarmupResult(0,0,false)
        val deadline=System.nanoTime()+budgetMs*1_000_000
        val served=mutableMapOf<String,Int>();var round=0;var saved=0
        coordinator.data{bodyCache=bodyCache.copy(running=true);refresh(messages=state.value.messages)}
        try {
            while(System.nanoTime()<deadline){
                currentCoroutineContext().ensureActive()
                val batches=coordinator.data{
                    val priority=notifications.contentPriorityIds()
                    val pending=ContentCacheRules.pending(state.value.messages,state.value.accounts,priority)
                    ContentCacheRules.batches(ContentCacheRules.eligible(pending,bodyFailures,System.currentTimeMillis()),priority,served)
                        .mapNotNull{messages->capture(messages.first().accountId)?.let{it to messages}}
                }
                if(batches.isEmpty())break
                round++;batches.forEach{served[it.first.account.id]=round}
                supervisorScope{batches.map{(request,missing)->async {
                    val account=request.account
                    try {
                        gateway.contents(account,vault.open(account.id,account.secret),missing,onItem={batch->
                            coordinator.data{
                                if(!current(request))return@data false
                                val now=state.value.messages.filter{it.accountId==account.id && SyncRules.remoteInbox(it)}
                                val merged=SyncRules.contentBatch(now,batch)
                                (batch.messages.map{it.id}+batch.missingIds).forEach{bodyFailures.remove(it)}
                                batch.failures.forEach{(id,error)->bodyFailures[id]=ContentCacheRules.failed(bodyFailures[id],System.currentTimeMillis(),"${account.name}: $error")}
                                saved+=batch.messages.size;bodyCache=bodyCache.copy(saved=saved)
                                if(batch.missingIds.isNotEmpty())coordinator.invalidate(account.id)
                                saveRemote(account.id,now,merged);saveBodyFailures()
                                batch.missingIds.isEmpty()
                            }
                        },onStart={m->coordinator.data{System.nanoTime()<deadline && current(request) && state.value.messages.any{it.id==m.id && !it.contentCached}}},
                            around={m,action->coordinator.content(m.id){
                                if(coordinator.data{db.message(m.id)?.contentCached==true})true else action()
                            }})
                    }catch(e: CancellationException){throw e}catch(e: Exception){coordinator.data{
                        if(current(request)){
                            missing.filter{m->state.value.messages.any{it.id==m.id && !it.contentCached}}.forEach{m->
                                bodyFailures[m.id]=ContentCacheRules.failed(bodyFailures[m.id],System.currentTimeMillis(),"${account.name}: ${friendlyError(e)}")
                            }
                            saveBodyFailures()
                        }
                    }}
                }}.awaitAll()}
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable){coordinator.data{
                val pending=ContentCacheRules.pending(state.value.messages,state.value.accounts,notifications.contentPriorityIds())
                val error=pending.mapNotNull{bodyFailures[it.id]?.error}.distinct().take(3).joinToString("\n")
                bodyCache=BodyCacheStatus(false,pending.size,saved,System.currentTimeMillis(),error)
                settings.edit().putInt("body-count",saved).putLong("body-end",bodyCache.finished).putString("body-error",error).apply()
                refresh(messages=state.value.messages)
            }}
        }
        coordinator.data{
            val pending=ContentCacheRules.pending(state.value.messages,state.value.accounts,notifications.contentPriorityIds())
            BodyWarmupResult(saved,pending.size,ContentCacheRules.eligible(pending,bodyFailures,System.currentTimeMillis()).isNotEmpty())
        }
    }}
    suspend fun saveDraft(draft: ComposeDraft)=withContext(Dispatchers.IO){coordinator.data{
        val a=db.accounts().find { it.id==draft.accountId }?:error("Выберите ящик отправителя.")
        val stored=db.message(draft.id)
        val merged=if(stored!=null)ForwardRules.mergeEditor(draft,stored.forwarding)else draft
        db.putMessage(merged.toMessage(a,"drafts").copy(delivery=if(draft.previousDeliveryUncertain)"uncertain" else ""));refresh()
    }}
    suspend fun deleteDraft(id: String)=withContext(Dispatchers.IO){coordinator.data{
        ForwardWorker.cancel(context,id);db.removeMessage(id);forwardProgress.update{it-id};forwarded.remove(id);refresh()
    }}
    suspend fun forward(message: MailItem,senderId: String): ComposeDraft=withContext(Dispatchers.IO){coordinator.data{
        val accounts=db.accounts()
        val sender=accounts.find{it.id==senderId}?:error("Ящик отправителя не найден.")
        require(sender.enabled){"Включите ящик отправителя в меню."}
        require(accounts.any{it.id==message.accountId}){"Исходный ящик не найден."}
        val draft=ComposeDraft(accountId=sender.id,subject=if(message.subject.startsWith("Fwd:",true))message.subject else "Fwd: ${message.subject}",
            body="\n\n──── Пересланное письмо ────\nОт: ${message.sender} <${message.senderEmail}>\nКому: ${message.recipients}\nТема: ${message.subject}\n\n${message.body}",
            forwarding=ForwardRules.plan(message,accounts.first{it.id==message.accountId}))
        db.putMessage(draft.toMessage(sender,"drafts"));refresh()
        if(draft.forwarding!=null)ForwardWorker.enqueue(context,draft.id)
        draft
    }}
    // Called only under the short data lock. Text/sender edits and parallel file results are retained.
    private fun updateForward(id: String,change: (ForwardPlan)->ForwardPlan): ForwardPlan? {
        val m=db.message(id)?.takeIf{it.localFolder=="drafts"}?:return null
        val changed=m.forwarding?.let(change)?:return null
        val live=forwardProgress.value[id]?.files.orEmpty().associateBy{it.key}
        val plan=changed.copy(files=changed.files.map{f->if(f.status==ForwardStatus.DOWNLOADING)f.copy(bytes=maxOf(f.bytes,live[f.key]?.bytes?:0))else f})
        db.putMessage(m.copy(forwarding=plan));forwardProgress.update{it+(id to plan)};refresh()
        return plan
    }
    suspend fun removeForwardFile(id: String,key: String)=withContext(Dispatchers.IO){coordinator.data{
        updateForward(id){it.copy(files=it.files.filterNot{file->file.key==key})}
    }}
    suspend fun retryForward(id: String)=withContext(Dispatchers.IO){coordinator.data{
        val plan=updateForward(id){it.copy(files=it.files.map{f->if(f.status==ForwardStatus.ERROR)f.copy(status=ForwardStatus.WAITING,bytes=0,error="")else f})}
        if(plan?.active==true)ForwardWorker.enqueue(context,id,androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE)
    }}
    suspend fun forwardFailed(id: String,error: String)=withContext(Dispatchers.IO){coordinator.data{
        updateForward(id){it.copy(files=it.files.map{f->if(f.status==ForwardStatus.WAITING || f.status==ForwardStatus.DOWNLOADING)f.copy(status=ForwardStatus.ERROR,error=error)else f})}
    }}
    private data class ForwardRead(val plan: ForwardPlan,val file: ForwardFile,val account: MailAccount?,val version: Long)
    suspend fun prepareForward(id: String)=withContext(Dispatchers.IO){forwardLocks.computeIfAbsent(id){Mutex()}.withLock{
        val initial=coordinator.data{
            db.message(id)?.takeIf{it.localFolder=="drafts"}?.forwarding?.also{p->forwardProgress.update{it+(id to p)}}
        }?:return@withLock
        val budget=ForwardBudget(initial.files.mapNotNull{f->f.prepared?.let{f.key to it.size}}.toMap())
        try {
            supervisorScope {
                initial.files.filter{it.status==ForwardStatus.WAITING || it.status==ForwardStatus.DOWNLOADING}.map{file->async {
                    forwardTransfers.withPermit{
                        val read=coordinator.data{
                            val plan=updateForward(id){p->p.copy(files=p.files.map{if(it.key==file.key)it.copy(status=ForwardStatus.DOWNLOADING,bytes=0,error="")else it})}?:return@data null
                            val task=plan.files.find{it.key==file.key}?:return@data null
                            ForwardRead(plan,task,db.accounts().find{it.id==plan.accountId},coordinator.version(plan.accountId))
                        }?:return@withPermit
                        try {
                            val coroutine=currentCoroutineContext()
                            var lastProgress=0L
                            val source=read.plan.sourceMessage()
                            val attachment=read.file.path?.let{Attachment(it,read.file.name,read.file.mime,read.file.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}
                            if(attachment!=null)require(read.account!=null && (read.plan.sourceKey.isEmpty() || read.plan.sourceKey==ForwardRules.sourceKey(read.account))){"Параметры исходного сервера изменены. Создайте пересылку из обновлённого письма."}
                            val cached=attachment?.let{attachmentCache.existing(source,it)}
                            val prepared=runInterruptible(Dispatchers.IO){
                                forwarded.prepareFile(id,file.key,file.name,file.mime,file.size,cached=cached,progress={bytes->
                                    coroutine.ensureActive()
                                    val keys=forwardProgress.value[id]?.files.orEmpty().map{it.key}.toSet()
                                    check(file.key in keys){"Вложение удалено из черновика."}
                                    budget.retain(keys)
                                    budget.progress(file.key,bytes)
                                    val now=System.nanoTime()
                                    if(now-lastProgress>=200_000_000){
                                        lastProgress=now
                                        forwardProgress.update{all->all[id]?.let{p->all+(id to p.copy(files=p.files.map{if(it.key==file.key && it.status==ForwardStatus.DOWNLOADING)it.copy(bytes=bytes)else it}))}?:all}
                                    }
                                }) { output->
                                    if(attachment!=null){
                                        val a=read.account?:error("Исходный ящик удалён.")
                                        require(a.enabled){"Включите исходный ящик для загрузки вложений."}
                                        gateway.attachment(a,vault.open(a.id,a.secret),source,attachment,output)
                                    }else (context.contentResolver.openInputStream(Uri.parse(requireNotNull(file.sourceUri)))?:error("Не удалось открыть ${file.name}"))
                                        .use{it.copyTo(output,64*1024)}
                                }
                            }
                            coordinator.data{
                                require(read.file.path==null || coordinator.version(read.plan.accountId)==read.version){"Исходный ящик изменён. Повторите подготовку вложения."}
                                if(attachment!=null && db.message(id)?.forwarding?.files?.any{it.key==file.key}==true)
                                    forwarded.existing(id,file.key,file.name)?.let{attachmentCache.remember(source,attachment,it)}
                                updateForward(id){p->p.copy(files=p.files.map{if(it.key==file.key)it.copy(status=ForwardStatus.READY,prepared=prepared,bytes=prepared.size,error="")else it})}
                            }
                        }catch(e: CancellationException){throw e}catch(e: Exception){
                            budget.remove(file.key)
                            coordinator.data{updateForward(id){p->p.copy(files=p.files.map{if(it.key==file.key)it.copy(status=ForwardStatus.ERROR,bytes=0,error=friendlyError(e))else it})}}
                        }
                    }
                }}.awaitAll()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable){coordinator.data{
                if(db.message(id)?.localFolder!="drafts"){forwardProgress.update{it-id};if(db.message(id)==null)forwarded.remove(id)}
            }}
        }
    }}
    suspend fun send(draft: ComposeDraft)=withContext(Dispatchers.IO){coordinator.data{
        val current=draft.copy(forwarding=db.message(draft.id)?.forwarding?:draft.forwarding)
        MailRules.validateDraft(current)?.let { error(it) }
        val ready=ForwardRules.sending(current)
        val a=db.accounts().find { it.id==draft.accountId }?:error("Ящик отправителя не найден.")
        require(a.enabled){"Включите ящик отправителя в меню."}
        db.putMessage(current.toMessage(a,"drafts").copy(delivery="sending"));refresh()
        try {
            gateway.send(a,vault.open(a.id,a.secret),ready)
            db.putMessage(ready.toMessage(a,"sent").copy(delivery="accepted"));forwardProgress.update{it-draft.id};refresh()
        } catch(e: Exception) {
            db.putMessage(current.toMessage(a,"drafts").copy(delivery=if(e is DeliveryUncertainException)"uncertain" else "failed"));forwardProgress.update{it-draft.id};refresh();throw e
        }
    }}
    private data class AttachmentRequest(val account: MailAccount,val version: Long)
    private suspend fun cachedAttachment(message: MailItem,file: Attachment): java.io.File=withContext(Dispatchers.IO){
        attachmentLocks.computeIfAbsent("${message.id}|${file.path}"){Mutex()}.withLock{
            coordinator.request(prepare={
                db.accounts().find{it.id==message.accountId}?.let{AttachmentRequest(it,coordinator.version(it.id))}
            },fetch={request->runInterruptible{
                val a=request.account
                attachmentCache.prepare(message,file){output->require(a.enabled){"Включите ящик для загрузки вложения."};gateway.attachment(a,vault.open(a.id,a.secret),message,file,output)}
            }},commit={request,cached->
                require(coordinator.version(request.account.id)==request.version && state.value.accounts.any{it.id==request.account.id}){"Ящик изменён или удалён. Повторите открытие вложения."}
                cached
            })?:error("Ящик не найден.")
        }
    }
    suspend fun download(message: MailItem,file: Attachment,uri: Uri)=withContext(Dispatchers.IO){
        try{
            val cached=cachedAttachment(message,file)
            context.contentResolver.openOutputStream(uri)?.use{output->cached.inputStream().use{it.copyTo(output,64*1024)}}?:error("Не удалось создать файл.")
        }catch(e: Exception){runCatching{android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)};throw e}
    }
    suspend fun openAttachment(message: MailItem,file: Attachment): OpenedAttachment=withContext(Dispatchers.IO){
        attachmentCache.opened(cachedAttachment(message,file),file)
    }
    private fun ComposeDraft.toMessage(a: MailAccount,folder: String)=MailItem(id,accountId,sender=a.senderName,senderEmail=a.email,recipients=to,
        subject=subject,body=body,date=System.currentTimeMillis(),localFolder=folder,outgoing=attachments,forwarding=forwarding)
}

