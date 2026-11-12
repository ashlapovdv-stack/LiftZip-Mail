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
    val poll: PollSettings = PollSettings(), val historyDownload: HistoryDownload = HistoryDownload(), val checks: Map<String,SyncCheck> = emptyMap(),val background: BackgroundCheck=BackgroundCheck(),val bodyCache: BodyCacheStatus=BodyCacheStatus(),val autoHidePanels: Boolean=true,val folders: List<ServerFolder> = emptyList(),val actions: List<MailAction> = emptyList(),val storage: StorageStatus=StorageStatus(),val style: MailStyle=MailStyle.STANDARD)
data class SyncResult(val newUnread: Int, val errors: List<String>)

class MailApplication : Application() {
    val repository by lazy { MailRepository(this) }
    val fastMail by lazy {FastMailController(this)}
    val processToken=UUID.randomUUID().toString()
    private val actions=BodyWarmupSignals()
    private val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    private val warmup=BodyWarmupSignals()
    @Volatile var visible=false
    override fun onTrimMemory(level: Int){super.onTrimMemory(level);ReaderDocuments.clear()}
    override fun onCreate(){super.onCreate();runCatching{connectivity.registerDefaultNetworkCallback(networkCallback)};scope.launchSafe{actions.collect({true},{repository.flushActions(60_000);BodyWarmupResult(0,0,false)})};scope.launchSafe{warmup.collect({visible},{repository.prefetchBodies(45_000)})};scope.launchSafe{
        repository.load()
        if(repository.snapshot.value.accounts.any{it.enabled}) {
            MailWorker.schedule(this@MailApplication);FolderWorker.enqueue(this@MailApplication);StorageWorker.enqueue(this@MailApplication)
            if(repository.snapshot.value.actions.any{it.active}){ActionWorker.enqueue(this@MailApplication);actions.request()}
        }else MailWorker.cancel(this@MailApplication)
    }}
    fun flushActions(){actions.request()}
    fun warmBodies(){if(visible)warmup.request()}
    private val connectivity by lazy{getSystemService(android.net.ConnectivityManager::class.java)}
    private val networkCallback=object: android.net.ConnectivityManager.NetworkCallback(){override fun onAvailable(network: android.net.Network){scope.launchSafe{repository.load();repository.networkAvailable()}}}

    private fun kotlinx.coroutines.CoroutineScope.launchSafe(action: suspend ()->Unit)=launch {
        try{action()}catch(e: CancellationException){throw e}catch(_: Exception){/* Durable work retries separately. */}
    }
}

class MailRepository(private val context: Context) {
    private val db=LocalStore(context)
    private val vault=SecretVault()
    private val gateway=MailGateway(context)
    private val attachmentCache=AttachmentCache(context)
    private val previews=PreviewStorage(context)
    private var storage=StorageStatus(limitMb=CacheRules.normalized(context.getSharedPreferences("mail-settings",Context.MODE_PRIVATE).getInt("cache-limit-mb",250)))
    private val queueFiles=QueueFiles(context)
    private val actionMutex=Mutex()
    private val folderLocks=ConcurrentHashMap<String,Mutex>()
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
        val actions=db.actions()
        val items=ActionRules.overlay(messages?:db.messages(),actions)
        state.value=MailSnapshot(accounts,items,accounts.associate { a->
            a.id to InboxHistory(if(syncPrefs.contains("count:${a.id}"))syncPrefs.getInt("count:${a.id}",0) else null,syncPrefs.getBoolean("older:${a.id}",true),
                if(syncPrefs.contains("unread:${a.id}"))syncPrefs.getInt("unread:${a.id}",0)else null)
        },settings.getString("default-sender",null)?.takeIf{id->accounts.any{it.id==id}},
            PollSettings.normalized(settings.getInt("foreground-minutes",1),settings.getInt("background-minutes",15)),readHistory(),
            accounts.associate{it.id to SyncCheck(syncPrefs.getLong("checked:${it.id}",0),syncPrefs.getString("error:${it.id}","").orEmpty(),syncPrefs.getLong("duration:${it.id}",0))},
            BackgroundCheck(settings.getLong("poll-start",0),settings.getLong("poll-end",0),settings.getString("poll-result","").orEmpty(),settings.getString("poll-error","").orEmpty(),settings.getInt("poll-arrived",0),
                settings.getString("poll-kind","auto").orEmpty(),settings.getLong("poll-auto-start",0),settings.getLong("poll-auto-end",0),settings.getString("poll-auto-result","").orEmpty()),
            bodyCache.copy(pending=pendingBodies(items,accounts).size),settings.getBoolean("auto-hide-panels",true),db.folders(),actions,storage,AppearancePreferences.read(settings))
        notifications.reconcile(SyncRules.activeMessages(state.value.messages,accounts,null),arrived,accounts){id->db.message(id)?.body?.take(3000)}
        notifications.history(state.value)
    }
    suspend fun load()=withContext(Dispatchers.IO){coordinator.data{
        val accounts=db.accounts()
        AccountColorSlots.assign(accounts).zip(accounts).filter{(assigned,old)->assigned.colorSlot!=old.colorSlot}
            .forEach{(assigned,_)->db.updateAccount(assigned,resetInbox=false)}
        val process=(context.applicationContext as MailApplication).processToken
        db.actions().forEach{a->val recovered=ActionRules.recovered(a,process);if(recovered!=a)db.putAction(recovered)}
        refresh()
        if(!settings.getBoolean("server-sync-initialized",false)) {
            val initial=state.value.messages.filter{it.uid==0L && it.localFolder in setOf("drafts","sent")}.mapNotNull{preview->db.message(preview.id)}
            db.applyActions(emptyList(),actions=initial.map{actionFor(it,if(it.localFolder=="sent")ActionKind.APPEND_SENT else ActionKind.APPEND_DRAFT)})
            settings.edit().putBoolean("server-sync-initialized",true).apply();refresh(messages=state.value.messages)
        }
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
    private fun protected(request: Request)=flagVersions.keys.filter{(flagVersions[it]?:0)!=(request.flags[it]?:0)}.toSet()+ActionRules.protectedIds(db.actions())
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
    suspend fun watchInbox(a: MailAccount,onConnected: (Boolean)->Unit,onChanged: ()->Unit)=withContext(Dispatchers.IO) {
        gateway.watchInbox(a,vault.open(a.id,a.secret),onConnected,onChanged)
    }
    suspend fun cachedMessage(id: String): MailItem?=withContext(Dispatchers.IO){db.message(id)?.let{it.copy(previewOnly=!it.contentCached)}}
    suspend fun message(id: String): MailItem?=withContext(Dispatchers.IO){coordinator.content(id){
        val cached=db.message(id)?:return@content null
        if(cached.contentCached && !MailContent.needsRenderUpdate(cached)){
            val now=System.currentTimeMillis()
            // Reopening the same letter must not repeatedly serialize its complete HTML to SQLite.
            if(now-cached.cachedAt<60_000L)return@content cached
            return@content coordinator.data {
                val current=db.message(id)?:return@data null
                if(now-current.cachedAt>=60_000L)db.putMessage(current.copy(cachedAt=now))
                current
            }
        }
        try {
            coordinator.request(prepare={capture(cached.accountId)?.let{request->request to (db.message(id)?:cached)}},fetch={(request,m)->
                val coroutine=currentCoroutineContext()
                if(m.contentCached && !MailContent.needsRenderUpdate(m))m else runInterruptible{gateway.content(request.account,vault.open(request.account.id,request.account.secret),m){coroutine.ensureActive()}}
            },commit={(request,_),full->
                require(current(request)){"Ящик приостановлен или его настройки изменились."}
                val now=state.value.messages.find{it.id==id}?:return@request null
                val result=full.copy(unread=now.unread,starred=now.starred,localFolder=now.localFolder,answered=full.answered || now.answered,forwarded=full.forwarded || now.forwarded)
                db.putMessage(result.copy(cachedAt=System.currentTimeMillis()));StorageWorker.enqueue(context);refresh(messages=state.value.messages.map{if(it.id==id)MailContent.preview(result)else it});result
            })?:error("Включите ящик в меню, чтобы загрузить текст письма.")
        }catch(e: CancellationException){throw e}catch(e: Exception){if(cached.contentCached)cached else throw e}
    }}
    suspend fun search(query: String): Set<String> =withContext(Dispatchers.IO){
        val coroutine=currentCoroutineContext();db.search(query){coroutine.ensureActive()}
    }
    suspend fun addAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO) {
        fun validate(){require(db.accounts().none{it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true)}){"Этот ящик уже добавлен."}}
        coordinator.data{validate()}
        runInterruptible{gateway.verify(account,password)}
        coordinator.data{validate();db.putAccount(account.copy(secret=vault.seal(account.id,password),colorSlot=AccountColorSlots.next(db.accounts().map{it.colorSlot})));refresh()}
        FolderWorker.enqueue(context)
    }
    suspend fun editAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO) {
        val (old,version)=coordinator.data {
            val old=db.accounts().find{it.id==account.id}?:error("Ящик не найден.")
            require(db.accounts().none{it.id!=old.id && it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true)}){"Этот ящик уже добавлен."}
            require(old.email.equals(account.email,true)){"Для другого адреса добавьте отдельный ящик."}
            MailRules.validateAccount(account,password,passwordRequired=false)?.let{error(it)}
            old to coordinator.version(old.id)
        }
        val secret=password.ifEmpty{vault.open(old.id,old.secret)}
        runInterruptible{gateway.verify(account,secret)}
        coordinator.data {
            require(coordinator.version(old.id)==version && db.accounts().any{it.id==old.id}){"Ящик изменён во время проверки. Повторите сохранение."}
            val reset=!SyncRules.sameInbox(old,account)
            if(reset)syncPrefs.edit().remove(old.id).remove("count:${old.id}").remove("unread:${old.id}").remove("older:${old.id}").remove("alert-validity:${old.id}").remove("alert-uid:${old.id}").remove("alert-ready:${old.id}").apply()
            coordinator.invalidate(old.id)
            if(reset)attachmentCache.removeAccount(old.id)
            db.updateAccount(account.copy(secret=vault.seal(old.id,secret),enabled=old.enabled,colorSlot=old.colorSlot),reset)
            db.actions().filter{it.accountId==old.id && it.status=="auth" && it.serverKey==AuthRules.serverKey(account)}.forEach{db.putAction(it.copy(status="pending",nextAttempt=0,error=""))}
            val history=readHistory()
            if(reset && old.id in history.accountIds && history.active)writeHistory(history.copy(status="paused",error="Настройки ящика изменены. Продолжите загрузку истории."))
            refresh()
        }
        wakeActions();FolderWorker.enqueue(context)
    }
    suspend fun removeAccount(id: String)=withContext(Dispatchers.IO){coordinator.data{
        val outgoing=db.messages().filter{it.accountId==id}.flatMap{it.outgoing}.map{it.uri}.toSet()
        val retained=db.messages().filter{it.accountId!=id}.flatMap{it.outgoing}.map{it.uri}.toSet()
        db.messages().filter{it.accountId==id}.forEach{ForwardWorker.cancel(context,it.id);forwardProgress.update{progress->progress-it.id};forwarded.remove(it.id)}
        queueFiles.removeUris(outgoing-retained)
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
        if(enabled)db.actions().filter{it.accountId==id && it.status=="pending"}.forEach{db.putAction(it.copy(nextAttempt=0))}
        refresh(messages=state.value.messages)
        if(enabled)wakeActions()
    }}
    suspend fun setDefaultAccount(id: String?)=withContext(Dispatchers.IO){coordinator.data{
        require(id==null || db.accounts().any{it.id==id}){"Ящик не найден."}
        settings.edit().putString("default-sender",id).apply();refresh()
    }}
    suspend fun setStyle(style: MailStyle)=withContext(Dispatchers.IO){coordinator.data{
        AppearancePreferences.write(settings,style);refresh(messages=state.value.messages)
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
                val deleting=db.actions().filter{ActionRules.folderMove(it.kind) && it.active}.map{it.messageId}.toSet()
                val pendingInWindow=merged.count{it.id in deleting && it.id in observedIds && SyncRules.remoteInbox(it)}
                val edit=syncPrefs.edit().putInt("count:$id",((total?:merged.size)-pendingInWindow).coerceAtLeast(0)).putLong("checked:$id",System.currentTimeMillis())
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
    private fun actionFor(m: MailItem,kind: ActionKind,unread: Boolean?=null,starred: Boolean?=null): MailAction {
        val id=kind.name+":"+m.id
        val previous=db.action(id)
        val original=if(kind==ActionKind.FLAGS && previous!=null)JSONObject(previous.payload)else db.encodeMessage(if(kind in setOf(ActionKind.FLAGS,ActionKind.MARK_ANSWERED,ActionKind.MARK_FORWARDED) || ActionRules.folderMove(kind) || kind==ActionKind.REMOVE_DRAFT)m.copy(body="",attachments=emptyList(),outgoing=emptyList(),forwarding=null)else m)
        m.sourceDraftId?.let{source->db.message(source)?.let{original.put("_origin",db.encodeMessage(it.copy(body="",htmlBody="",composer=null,attachments=emptyList(),outgoing=emptyList(),forwarding=null)))
            state.value.accounts.find{a->a.id==it.accountId}?.let{a->original.put("_originServer",AuthRules.serverKey(a))}}}
        val account=state.value.accounts.find{it.id==m.accountId}?:error("Ящик не найден.")
        return MailAction(id,m.accountId,m.id,kind,original.toString(),maxOf((previous?.revision?:0)+1,m.editRevision),m.subject,AuthRules.serverKey(account),
            unread?:previous?.unread,starred?:previous?.starred,created=previous?.created?:System.currentTimeMillis())
    }
    private fun wakeActions() {
        ActionWorker.enqueue(context)
        (context.applicationContext as MailApplication).flushActions()
    }
    suspend fun changeFlags(message: MailItem,unread: Boolean?=null,starred: Boolean?=null)=withContext(Dispatchers.IO) {
        coordinator.data {
            val now=state.value.messages.find{it.id==message.id}?:return@data
            val changed=now.copy(unread=unread?:now.unread,starred=starred?:now.starred)
            adjustUnreadCount(now.accountId,listOf(now),listOf(changed))
            db.applyActions(listOf(changed),actions=if(FolderRules.remote(now))listOf(actionFor(now,ActionKind.FLAGS,unread,starred))else emptyList())
            flagsChanged(listOf(now.id));refresh()
        }
        wakeActions()
    }
    suspend fun markRead(ids: Set<String>,unread: Boolean): FlagChange=withContext(Dispatchers.IO) {
        val changed=coordinator.data {
            val rows=state.value.messages.filter{it.id in ids && !it.pendingDelete}
            val updated=rows.map{it.copy(unread=unread)}
            rows.groupBy{it.accountId}.forEach{(id,before)->adjustUnreadCount(id,before,updated.filter{it.accountId==id})}
            db.applyActions(updated,actions=rows.filter(FolderRules::remote).map{actionFor(it,ActionKind.FLAGS,unread=unread)})
            flagsChanged(rows.map{it.id});refresh();rows.map{it.id}.toSet()
        }
        wakeActions();FlagChange(changed,emptyList())
    }
    suspend fun deleteMessages(ids: Set<String>): DeleteResult=withContext(Dispatchers.IO) {
        val result=coordinator.data {
            val chosen=state.value.messages.filter{it.id in ids && !it.pendingDelete}
            val remote=chosen.filter{FolderRules.remote(it) || it.uid==0L && it.localFolder=="sent"}
            val local=chosen.filter{TrashRules.local(it) && it.localFolder!="sent"}
            val actions=remote.map{actionFor(it,ActionKind.TRASH)}.toMutableList()
            remote.filter{it.uid==0L}.forEach{m->db.action(ActionKind.APPEND_SENT.name+":"+m.id)?.let{db.completeAction(it)}}
            local.forEach{m->
                db.action(ActionKind.APPEND_DRAFT.name+":"+m.id)?.let{db.completeAction(it)}
                m.sourceDraftId?.let{db.message(it)}?.let{actions+=actionFor(it,ActionKind.REMOVE_DRAFT)}
                ForwardWorker.cancel(context,m.id)
            }
            val pending=remote.map{it.copy(pendingDelete=true)}+actions.filter{it.kind==ActionKind.REMOVE_DRAFT}.mapNotNull{db.message(it.messageId)?.copy(pendingDelete=true)}
            db.applyActions(pending,remove=local.map{it.id}.toSet(),actions=actions)
            remote.groupBy{it.accountId}.forEach{(id,rows)->
                val edit=syncPrefs.edit()
                if(syncPrefs.contains("unread:$id"))edit.putInt("unread:$id",TrashRules.unreadAfter(syncPrefs.getInt("unread:$id",0),rows))
                if(syncPrefs.contains("count:$id"))edit.putInt("count:$id",(syncPrefs.getInt("count:$id",0)-rows.count(SyncRules::remoteInbox)).coerceAtLeast(0))
                edit.apply()
            }
            refresh()
            DeleteResult((remote+local).map{it.id}.toSet(),if(chosen.size!=remote.size+local.size)listOf("Дождитесь окончания отправки, чтобы удалить оставшиеся письма.")else emptyList())
        }
        wakeActions();result
    }
    suspend fun spamMessages(ids: Set<String>,toInbox: Boolean): DeleteResult=withContext(Dispatchers.IO) {
        val result=coordinator.data {
            val chosen=state.value.messages.filter{it.id in ids && !it.pendingDelete}
            val rows=chosen.filter{SpamRules.eligible(it,toInbox)}
            val kind=if(toInbox)ActionKind.RESTORE_INBOX else ActionKind.SPAM
            db.applyActions(rows.map{it.copy(pendingDelete=true)},actions=rows.map{actionFor(it,kind)})
            spamCounts(rows,-1)
            refresh()
            DeleteResult(rows.map{it.id}.toSet(),if(rows.size<chosen.size)listOf("Для части выбранных писем это действие недоступно.")else emptyList())
        }
        wakeActions();result
    }
    private fun spamCounts(rows: List<MailItem>,delta: Int) {
        rows.filter(SyncRules::remoteInbox).groupBy{it.accountId}.forEach{(id,messages)->
            val edit=syncPrefs.edit()
            if(syncPrefs.contains("count:$id"))edit.putInt("count:$id",(syncPrefs.getInt("count:$id",0)+delta*messages.size).coerceAtLeast(0))
            if(syncPrefs.contains("unread:$id"))edit.putInt("unread:$id",(syncPrefs.getInt("unread:$id",0)+delta*messages.count{it.unread}).coerceAtLeast(0))
            edit.apply()
        }
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
                val history=accounts.associate{it.id to InboxHistory(hasOlder=state.value.history[it.id]?.hasOlder!=false || historyBodies(it.id,state.value.messages,issues).isNotEmpty())}
                val accountId=SyncRules.nextHistoryAccount(job,accounts,history,skipped)
                if(accountId==null) {
                    val retry=SyncRules.nextHistoryAccount(job,accounts,history,emptySet())!=null
                    val disabled=accounts.any{!it.enabled && it.id in job.accountIds && history[it.id]?.hasOlder==true}
                    writeHistory(job.copy(status=if(retry)"waiting" else if(disabled)"paused" else if(issues.isNotEmpty())"partial" else "done",issues=issues,phase="",currentUid=0,error=if(retry)job.error else if(disabled)"Включите приостановленные ящики в меню и продолжите загрузку." else ""))
                    refresh(messages=state.value.messages);stopped=HistoryStep(retry=retry);null
                }else capture(accountId)?.let{request->
                    val missing=if(state.value.history[accountId]?.hasOlder!=false)emptyList() else historyBodies(accountId,request.cached,issues).take(10)
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
                            saveRemote(a.id,now,merged);StorageWorker.enqueue(context)
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
        if(pendingBodies().isNotEmpty()){
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
        val pending=coordinator.data{pendingBodies().isNotEmpty()}
        if(pending){NotificationPreviewWorker.enqueue(context);(context.applicationContext as MailApplication).warmBodies()}
    }
    suspend fun retryBodyWarmup()=withContext(Dispatchers.IO){
        coordinator.data{bodyFailures.clear();saveBodyFailures();bodyCache=bodyCache.copy(error="");refresh(messages=state.value.messages)}
        requestBodyWarmup()
    }
    /** Same pipeline is used in the polling worker and while visible; only one pipeline owns a batch. */
    suspend fun prefetchBodies(budgetMs: Long=120_000): BodyWarmupResult=withContext(Dispatchers.IO){bodyMutex.withLock{
        if(coordinator.data{pendingBodies().isEmpty()})return@withLock BodyWarmupResult(0,0,false)
        val deadline=System.nanoTime()+budgetMs*1_000_000
        val served=mutableMapOf<String,Int>();var round=0;var saved=0
        coordinator.data{bodyCache=bodyCache.copy(running=true);refresh(messages=state.value.messages)}
        try {
            while(System.nanoTime()<deadline){
                currentCoroutineContext().ensureActive()
                val batches=coordinator.data{
                    val priority=notifications.contentPriorityIds()
                    val pending=pendingBodies()
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
                                saveRemote(account.id,now,merged);saveBodyFailures();StorageWorker.enqueue(context)
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
                val pending=pendingBodies()
                val error=pending.mapNotNull{bodyFailures[it.id]?.error}.distinct().take(3).joinToString("\n")
                bodyCache=BodyCacheStatus(false,pending.size,saved,System.currentTimeMillis(),error)
                settings.edit().putInt("body-count",saved).putLong("body-end",bodyCache.finished).putString("body-error",error).apply()
                refresh(messages=state.value.messages)
            }}
        }
        coordinator.data{
            val pending=pendingBodies()
            BodyWarmupResult(saved,pending.size,ContentCacheRules.eligible(pending,bodyFailures,System.currentTimeMillis()).isNotEmpty())
        }
    }}
    private fun historyBodies(accountId: String,messages: List<MailItem>,issues: List<HistoryIssue>): List<MailItem> {
        val allowed=CacheRules.warmSources(state.value.messages,notifications.contentPriorityIds(),CacheRules.bodyBudget(storage.limitMb)).map{it.id}.toSet()
        return HistoryRules.pendingBodies(accountId,messages,issues).filter{it.id in allowed}
    }
    private fun pendingBodies(messages: List<MailItem> = state.value.messages,accounts: List<MailAccount> = state.value.accounts): List<MailItem> {
        val priority=notifications.contentPriorityIds()
        val active=accounts.filter{it.enabled}.map{it.id}.toSet()
        val source=CacheRules.warmSources(messages.filter{it.accountId in active},priority,CacheRules.bodyBudget(storage.limitMb))
        return ContentCacheRules.pending(source,accounts,priority)
    }
    suspend fun storageStatus()=withContext(Dispatchers.IO){coordinator.data {
        val cached=db.messages().filter{FolderRules.remote(it) && it.contentCached}
        storage=storage.copy(bodyBytes=cached.sumOf{it.cacheBytes},attachmentBytes=previews.bytes(),cachedBodies=cached.size,checkedAt=System.currentTimeMillis())
        refresh(messages=state.value.messages)
    }}
    suspend fun setCacheLimit(value: Int)=withContext(Dispatchers.IO) {
        coordinator.data{storage=storage.copy(limitMb=CacheRules.normalized(value));settings.edit().putInt("cache-limit-mb",storage.limitMb).apply();refresh(messages=state.value.messages)}
        trimCache()
    }
    suspend fun trimCache(clear: Boolean=false)=withContext(Dispatchers.IO) {
        coordinator.data {
            val protected=db.actions().map{it.messageId}.toSet()+state.value.messages.filter{it.localFolder in setOf("drafts","outbox")}.flatMap{listOfNotNull(it.sourceDraftId,it.forwarding?.messageId)}
            val ids=CacheRules.evict(db.messages(),protected,CacheRules.bodyBudget(storage.limitMb),clear)
            if(ids.isNotEmpty()) {
                db.putMessages(ids.mapNotNull{db.message(it)}.map{it.copy(body=it.body.take(MailContent.PREVIEW_LENGTH),htmlBody="",contentVersion=0,contentCached=false,previewOnly=false)})
                bodyFailures.keys.removeAll(ids);saveBodyFailures();refresh()
            }
        }
        previews.trim(CacheRules.fileBudget(storage.limitMb),clear)
        if(clear)db.compact()
        storageStatus()
    }
    suspend fun saveDraft(draft: ComposeDraft)=withContext(Dispatchers.IO) {
        coordinator.data {
            val account=db.accounts().find{it.id==draft.accountId}?:error("Выберите ящик отправителя.")
            val stored=db.message(draft.id)
            require(stored?.localFolder!="outbox"){"Письмо уже в Исходящих. Сначала отмените его отправку."}
            val merged=if(stored!=null)ForwardRules.mergeEditor(draft,stored.forwarding)else draft
            val m=merged.toMessage(account,"drafts").copy(editRevision=(stored?.editRevision?:0)+1,sourceDraftId=stored?.sourceDraftId?:draft.sourceDraftId)
            db.applyActions(listOf(m),actions=listOf(actionFor(m,ActionKind.APPEND_DRAFT)));refresh()
        }
        wakeActions()
    }
    suspend fun deleteDraft(id: String) {deleteMessages(setOf(id))}
    private suspend fun sourceHeaders(source: MailItem): MessageHeaders {
        source.headers?.let{return it}
        if(source.uid<=0)return MessageHeaders(messageId=source.internetMessageId)
        val (account,version)=coordinator.data {
            val account=db.accounts().find{it.id==source.accountId && it.enabled}?:error("Включите исходный ящик для подготовки ответа.")
            account to coordinator.version(account.id)
        }
        val headers=runInterruptible{gateway.replyHeaders(account,vault.open(account.id,account.secret),source)}
        coordinator.data {
            require(coordinator.version(account.id)==version){"Параметры исходного ящика изменились."}
            db.message(source.id)?.takeIf{it.uid==source.uid && it.validity==source.validity}?.let{db.putMessage(it.copy(headers=headers))}
        }
        return headers
    }
    suspend fun reply(message: MailItem): ComposeDraft=prepareResponse(message,message.accountId,"reply")
    suspend fun forward(message: MailItem,senderId: String): ComposeDraft=prepareResponse(message,senderId,"forward")
    private suspend fun prepareResponse(message: MailItem,senderId: String,kind: String): ComposeDraft=withContext(Dispatchers.IO) {
        var source=this@MailRepository.message(message.id)?:error("Исходное письмо не найдено.")
        if(kind=="reply" && source.headers==null) {
            try {source=source.copy(headers=sourceHeaders(source))}
            catch(e: CancellationException){throw e}catch(_: Exception){/* Offline editing remains available; resolve before enqueue. */}
        }
        coordinator.data {
            val accounts=db.accounts()
            val sender=accounts.find{it.id==senderId && it.enabled}?:error("Включите ящик отправителя в меню.")
            val prefix=if(kind=="reply")"Re:" else "Fwd:"
            val draft=ComposeDraft(accountId=sender.id,to=if(kind=="reply")ReplyHeaders.recipient(source)else "",
                subject=if(source.subject.startsWith(prefix,true))source.subject else "$prefix ${source.subject}",
                quote=ComposeContent.quote(source,kind).copy(serverKey=accounts.find{it.id==source.accountId}?.let(AuthRules::serverKey).orEmpty()),forwarding=ForwardRules.plan(source,accounts.find{it.id==source.accountId},includeOrdinary=kind=="forward"),
                responseTo=source.id,responseKind=kind)
            db.putMessage(draft.toMessage(sender,"drafts"));refresh()
            if(draft.forwarding!=null)ForwardWorker.enqueue(context,draft.id)
            draft
        }
    }
    private suspend fun resolveReply(draft: ComposeDraft): ComposeDraft {
        val quote=draft.quote?.takeIf{it.kind=="reply" && it.headers==null}?:return draft
        if(quote.serverKey.isNotBlank())coordinator.data {
            val account=db.accounts().find{it.id==quote.accountId}?:error("Исходный ящик удалён. Не удалось подготовить заголовки ответа.")
            require(AuthRules.serverKey(account)==quote.serverKey){"Параметры исходного сервера изменились. Откройте исходное письмо заново."}
        }
        val headers=sourceHeaders(quote.sourceMessage())
        // Respect an explicitly edited recipient; only complete our original default.
        val to=if(draft.to==quote.senderEmail && headers.replyTo.isNotBlank())headers.replyTo else draft.to
        return draft.copy(to=to,quote=quote.copy(headers=headers))
    }
    // Called only under the short data lock. Text/sender edits and parallel file results are retained.
    private fun updateForward(id: String,change: (ForwardPlan)->ForwardPlan): ForwardPlan? {
        val m=db.message(id)?.takeIf{it.localFolder=="drafts"}?:return null
        val changed=m.forwarding?.let(change)?:return null
        if(changed.ready)db.action(ActionKind.APPEND_DRAFT.name+":"+id)?.takeIf{it.status=="pending"}?.let{db.putAction(it.copy(nextAttempt=0,error=""));wakeActions()}
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
                            }.copy(contentId=read.file.contentId)
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
    suspend fun send(draft: ComposeDraft)=withContext(Dispatchers.IO) {
        MailRules.validateDraft(draft)?.let{error(it)}
        val resolved=resolveReply(draft)
        MailRules.validateDraft(resolved)?.let{error(it)}
        val flattened=ForwardRules.sending(resolved).copy(timestamp=System.currentTimeMillis())
        ComposeContent.requireResources(flattened)
        val frozen=queueFiles.freeze(flattened)
        var queued=false
        try {coordinator.data {
            val account=db.accounts().find{it.id==draft.accountId}?:error("Ящик отправителя не найден.")
            val old=db.message(draft.id)
            require(old?.localFolder!="outbox"){"Письмо уже находится в Исходящих."}
            val m=frozen.toMessage(account,"outbox").copy(delivery="queued",sourceDraftId=old?.sourceDraftId?:draft.sourceDraftId,editRevision=(old?.editRevision?:0)+1)
            db.applyActions(listOf(m),actions=listOf(actionFor(m,ActionKind.SEND)),removeActions=setOf(ActionKind.APPEND_DRAFT.name+":"+m.id))
            queued=true
            forwardProgress.update{it-draft.id};refresh()
        }}catch(e: Exception){if(!queued)queueFiles.removeUris(frozen.attachments.map{it.uri}.toSet());throw e}
        wakeActions()
    }
    suspend fun discoverFolders(accountId: String?=null)=withContext(Dispatchers.IO) {
        val accounts=coordinator.data{state.value.accounts.filter{it.enabled && (accountId==null || it.id==accountId)}}
        for(a in accounts) {
            val version=coordinator.data{coordinator.version(a.id)}
            val folders=runInterruptible{gateway.folders(a,vault.open(a.id,a.secret))}
            coordinator.data {
                if(coordinator.version(a.id)!=version || db.accounts().none{it.id==a.id && it.enabled})return@data
                val existing=db.folders().filter{it.accountId==a.id}.associateBy{it.path}
                db.replaceFolders(a.id,folders.map{f->existing[f.path]?.let{f.copy(total=it.total,unread=it.unread,validity=it.validity,hasOlder=it.hasOlder,checkedAt=it.checkedAt,syncAll=it.syncAll,syncing=false,error=it.error)}?:f})
                val removed=existing.keys-folders.map{it.path}.toSet()
                val obsolete=state.value.messages.filter{it.accountId==a.id && FolderRules.remote(it) && it.remoteFolder in removed}.map{it.id}.toSet()
                if(obsolete.isNotEmpty())db.removeMessages(obsolete)
                refresh(messages=state.value.messages.filter{it.id !in obsolete})
            }
        }
    }
    suspend fun syncFolder(accountId: String,path: String,older: Boolean=false)=withContext(Dispatchers.IO){folderLocks.computeIfAbsent(accountId+"|"+path){Mutex()}.withLock {
        var folder: ServerFolder?=null
        try {
            coordinator.request(prepare={
                val f=db.folders().find{it.accountId==accountId && it.path==path}?:return@request null
                if(f.role==FolderRole.INBOX)return@request null
                folder=f
                val captured=capture(accountId)?:return@request null
                val cache=state.value.messages.filter{it.accountId==accountId && FolderRules.remote(it) && it.remoteFolder==path}
                db.putFolders(listOf(f.copy(syncing=true,error="")));refresh(messages=state.value.messages)
                captured.copy(cached=cache) to f
            },fetch={(r,f)->runInterruptible{gateway.inbox(r.account,vault.open(accountId,r.account.secret),r.cached,loadOlder=older,pageSize=100,folderPath=f.path,role=f.role)}},commit={(r,f),page->
                if(!current(r))return@request
                val now=state.value.messages.filter{it.accountId==accountId && FolderRules.remote(it) && it.remoteFolder==path}
                val byId=now.associateBy{it.id}
                val localCopies=state.value.messages.filter{it.accountId==accountId && it.uid==0L && FolderRules.sent(it) && it.internetMessageId.isNotBlank()}.associateBy{it.internetMessageId}
                val incoming=page.messages.map{m->
                    val local=localCopies[m.internetMessageId]
                    val current=byId[m.id]
                    val reused=local?:current
                    val withBody=(if(reused?.contentCached==true)m.copy(id=local?.id?:m.id,body=reused.body,htmlBody=reused.htmlBody,contentVersion=reused.contentVersion,cacheBytes=reused.cacheBytes,attachments=reused.attachments,outgoing=reused.outgoing,contentCached=true,previewOnly=reused.previewOnly)else m)
                        .copy(answered=m.answered || current?.answered==true,forwarded=m.forwarded || current?.forwarded==true)
                    if(local!=null) {
                        if(local.unread!=m.unread || local.starred!=m.starred)db.putAction(actionFor(withBody,ActionKind.FLAGS,unread=local.unread,starred=local.starred))
                        withBody.copy(unread=local.unread,starred=local.starred)
                    }else if(m.id in protected(r) && current!=null)withBody.copy(unread=current.unread,starred=current.starred)else withBody
                }
                val ids=incoming.map{it.id}.toSet()
                val merged=(if(older)now.filter{it.validity==page.validity && it.id !in ids}else emptyList())+incoming
                db.applyInbox(now,merged)
                if(f.validity>0 && f.validity!=page.validity)coordinator.invalidate(accountId)
                val latest=db.folders().find{it.accountId==accountId && it.path==path}?:f
                db.putFolders(listOf(latest.copy(total=page.history.total,unread=page.history.unread?:latest.unread,validity=page.validity?:0,hasOlder=page.history.hasOlder,
                    checkedAt=System.currentTimeMillis(),syncing=false,syncAll=latest.syncAll && page.history.hasOlder,error="")))
                refresh()
            })
        }catch(e: CancellationException){throw e}catch(e: Exception){coordinator.data {
            folder?.let{f->db.putFolders(listOf(f.copy(syncing=false,checkedAt=System.currentTimeMillis(),error=friendlyError(e))))};refresh(messages=state.value.messages)
        }}finally{withContext(kotlinx.coroutines.NonCancellable){coordinator.data{db.folders().find{it.accountId==accountId && it.path==path && it.syncing}?.let{db.putFolders(listOf(it.copy(syncing=false)))};refresh(messages=state.value.messages)}}}
    }}
    suspend fun folderStep(): Boolean=withContext(Dispatchers.IO) {
        val target=coordinator.data {
            val active=db.accounts().filter{it.enabled}.map{it.id}.toSet();val now=System.currentTimeMillis()
            db.folders().filter{it.accountId in active && it.role!=FolderRole.INBOX && !it.syncing && (it.error.isBlank() || now-it.checkedAt>60_000) && (it.syncAll && it.hasOlder || now-it.checkedAt>15*60_000)}
                .sortedWith(compareBy<ServerFolder>{if(it.syncAll)0 else if(it.role in setOf(FolderRole.DRAFTS,FolderRole.SENT))1 else 2}.thenBy{it.checkedAt}).firstOrNull()
        }?:return@withContext false
        syncFolder(target.accountId,target.path,older=target.syncAll && target.checkedAt>0)
        true
    }
    suspend fun pendingFolderHistory()=coordinator.data{val active=db.accounts().filter{it.enabled}.map{it.id}.toSet();db.folders().any{it.accountId in active && it.syncAll && it.hasOlder}}
    suspend fun pauseFolderHistory(accountId: String,path: String)=withContext(Dispatchers.IO){coordinator.data{db.folders().find{it.accountId==accountId && it.path==path}?.let{db.putFolders(listOf(it.copy(syncAll=false)))};refresh(messages=state.value.messages)}}
    suspend fun startFolderHistories(folders: List<ServerFolder>)=withContext(Dispatchers.IO) {
        coordinator.data {
            val chosen=folders.map{it.accountId to it.path}.toSet()
            val active=db.accounts().filter{it.enabled}.map{it.id}.toSet()
            db.putFolders(db.folders().filter{it.role!=FolderRole.INBOX && it.accountId in active && (it.accountId to it.path) in chosen}.map{it.copy(syncAll=it.hasOlder,error="")})
            refresh(messages=state.value.messages)
        };FolderWorker.enqueue(context)
    }
    suspend fun loadFolderHistory(accountId: String,path: String)=withContext(Dispatchers.IO) {
        coordinator.data{db.folders().find{it.accountId==accountId && it.path==path}?.let{db.putFolders(listOf(it.copy(syncAll=true,error="")))};refresh(messages=state.value.messages)}
        FolderWorker.enqueue(context)
    }
    suspend fun draftForEditing(id: String): ComposeDraft=withContext(Dispatchers.IO) {
        val m=message(id)?:error("Черновик не найден.")
        if(m.localFolder=="drafts")return@withContext ComposerHtml.forEditing(m.draft())
        require(m.localFolder=="remote-drafts"){"Это письмо не является черновиком."}
        coordinator.data {
            db.messages().firstOrNull{it.localFolder=="drafts" && it.sourceDraftId==m.id}?.let{return@data ComposerHtml.forEditing((db.message(it.id)?:it).draft())}
            val account=db.accounts().find{it.id==m.accountId}?:error("Ящик не найден.")
            val draft=ComposeDraft(accountId=m.accountId,to=m.composer?.to?:HeaderText.editableRecipients(m.recipients),subject=m.composer?.subject?:m.subject,body=m.composer?.text?:m.body,bodyHtml=m.composer?.bodyHtml?:m.htmlBody.takeIf{m.composer==null && it.isNotBlank()}?.let(ComposerHtml::clean),forwarding=ForwardRules.plan(m,account,restoreDraft=true),quote=m.composer?.quote?:m.takeIf{m.composer==null && it.htmlBody.isNotBlank()}?.let{ComposeContent.quote(it,"draft").copy(plain="",html="")},quoteIncluded=m.composer?.quoteIncluded?:true,responseTo=m.composer?.responseTo?:m.composer?.quote?.messageId,responseKind=m.composer?.responseKind?.takeIf{it.isNotBlank()}?:m.composer?.quote?.kind.orEmpty(),cc=m.composer?.cc?:HeaderText.editableRecipients(m.cc),bcc=m.composer?.bcc?:HeaderText.editableRecipients(m.bcc),internetMessageId=m.internetMessageId,sourceDraftId=m.id)
            db.putMessage(draft.toMessage(account,"drafts"));refresh()
            if(draft.forwarding!=null)ForwardWorker.enqueue(context,draft.id)
            draft
        }
    }
    private fun sameActionServer(a: MailAction)=db.accounts().any{it.id==a.accountId && AuthRules.serverKey(it)==a.serverKey}
    private fun actionMessage(a: MailAction)=db.decodeMessage(JSONObject(a.payload))
    private fun actionOrigin(a: MailAction)=JSONObject(a.payload).optJSONObject("_origin")?.let{db.decodeMessage(it)}
    private fun trustedOrigin(action: MailAction): MailItem? {
        val origin=actionOrigin(action)?:return null
        val owner=state.value.accounts.find{it.id==origin.accountId}?:return null
        val key=JSONObject(action.payload).optString("_originServer").ifBlank{if(origin.accountId==action.accountId)action.serverKey else ""}
        return origin.takeIf{key.isNotBlank() && key==AuthRules.serverKey(owner)}
    }
    private fun MailItem.draft()=ComposeDraft(id,accountId,composer?.to?:recipients,composer?.subject?:subject,composer?.text?:body,outgoing,delivery=="uncertain",forwarding,internetMessageId,sourceDraftId,date,composer?.responseTo?:responseTo,composer?.responseKind?.takeIf{it.isNotBlank()}?:responseKind,
        cc=composer?.cc?:cc,bcc=composer?.bcc?:bcc,quote=composer?.quote,quoteIncluded=composer?.quoteIncluded?:true,bodyHtml=composer?.bodyHtml)
    suspend fun networkAvailable() {
        coordinator.data{refresh(messages=state.value.messages)}
        if(snapshot.value.accounts.any{it.enabled}){wakeActions();requestBodyWarmup()}
    }
    suspend fun nextActionDelay(): Long?=withContext(Dispatchers.IO){coordinator.data{
        val accounts=db.accounts().filter{it.enabled}.map{it.id}.toSet()
        db.actions().filter{it.accountId in accounts && it.status=="pending"}.minOfOrNull{(it.nextAttempt-System.currentTimeMillis()).coerceAtLeast(0)}
    }}
    suspend fun flushActions(budgetMs: Long)=withContext(Dispatchers.IO){actionMutex.withLock {
        if(!MailNotifications.access(context).connected)return@withLock
        val started=android.os.SystemClock.elapsedRealtime()
        while(android.os.SystemClock.elapsedRealtime()-started<budgetMs) {
            currentCoroutineContext().ensureActive()
            val claimed=coordinator.data {
                val accounts=db.accounts();val now=System.currentTimeMillis()
                val next=db.actions().firstOrNull{ActionRules.ready(it,accounts,now)}?:return@data emptyList<MailAction>()
                val message=actionMessage(next)
                val batch=if(next.kind==ActionKind.FLAGS || ActionRules.folderMove(next.kind))db.actions().filter{a->
                    ActionRules.ready(a,accounts,now) && a.accountId==next.accountId && a.serverKey==next.serverKey && a.kind==next.kind && a.unread==next.unread && a.starred==next.starred &&
                        actionMessage(a).let{message.uid>0 && it.uid>0 && it.remoteFolder==message.remoteFolder && it.validity==message.validity}
                }.take(100)else listOf(next)
                val process=(context.applicationContext as MailApplication).processToken
                batch.map{it.copy(status=if(it.kind==ActionKind.SEND)"sending" else "running",lease=process)}.onEach{a->
                    db.putAction(a)
                    if(a.kind==ActionKind.SEND)db.message(a.messageId)?.let{db.putMessage(it.copy(delivery="sending"))}
                }.also{refresh()}
            }
            if(claimed.isEmpty())break
            val first=claimed.first()
            try {
                val account=coordinator.data{db.accounts().find{it.id==first.accountId && it.enabled}}?:error("Ящик выключен или удалён.")
                if(first.kind!=ActionKind.SEND)require(first.serverKey==AuthRules.serverKey(account)){"Сервер ящика изменился. Проверьте действие перед повтором."}
                val password=vault.open(account.id,account.secret)
                when(first.kind) {
                    ActionKind.FLAGS -> {
                        val remote=claimed.map(::actionMessage)
                        val uids=runInterruptible{gateway.flagsMany(account,password,remote,seen=first.unread?.not(),starred=first.starred)}
                        coordinator.data {
                            if(!sameActionServer(first)){claimed.forEach{db.completeAction(it)};refresh();return@data}
                            claimed.forEach{a->
                                val m=db.message(a.messageId)
                                val updated=m?.copy(unread=a.unread?:m.unread,starred=a.starred?:m.starred)
                                if(actionMessage(a).uid in uids) {
                                    if(!db.finishAction(a,items=listOfNotNull(updated))) {
                                        db.action(a.id)?.takeIf{it.kind==ActionKind.FLAGS}?.let{newer->
                                            val base=actionMessage(newer).copy(unread=a.unread?:actionMessage(newer).unread,starred=a.starred?:actionMessage(newer).starred)
                                            db.replaceAction(newer,newer.copy(payload=db.encodeMessage(base).toString()))
                                        }
                                    }
                                }else db.finishAction(a,remove=setOf(a.messageId))
                            }
                            coordinator.invalidate(account.id);refresh()
                        }
                    }
                    ActionKind.MARK_ANSWERED,ActionKind.MARK_FORWARDED -> {
                        val original=actionMessage(first)
                        runInterruptible{gateway.markResponse(account,password,original,first.kind==ActionKind.MARK_FORWARDED)}
                        coordinator.data{if(sameActionServer(first))db.finishAction(first) else db.completeAction(first);refresh()}
                    }
                    ActionKind.TRASH -> {
                        val source=actionMessage(first)
                        if(source.uid==0L) {
                            val copies=runInterruptible{gateway.sentCopies(account,password,source)}
                            copies.forEach{copy->gateway.trashMany(account,password,listOf(copy),before={coordinator.data{sameActionServer(first)}},onConfirmed={true})}
                            coordinator.data{if(sameActionServer(first)){db.finishAction(first,remove=setOf(first.messageId));coordinator.invalidate(account.id)};refresh()}
                        }else gateway.trashMany(account,password,claimed.map(::actionMessage),
                            before={coordinator.data{require(db.accounts().any{it.id==account.id && it.enabled && AuthRules.serverKey(it)==first.serverKey}){"Ящик выключен или его сервер изменился."};true}},onConfirmed={uids->coordinator.data {
                                if(!sameActionServer(first)){claimed.forEach{db.completeAction(it)};refresh();return@data false}
                                claimed.filter{actionMessage(it).uid in uids}.forEach{a->db.finishAction(a,remove=setOf(a.messageId))}
                                coordinator.invalidate(account.id);refresh();true
                            }})
                    }
                    ActionKind.SPAM,ActionKind.RESTORE_INBOX -> {
                        val role=if(first.kind==ActionKind.SPAM)FolderRole.JUNK else FolderRole.INBOX
                        val target=gateway.moveMany(account,password,claimed.map(::actionMessage),role,
                            before={coordinator.data{require(db.accounts().any{it.id==account.id && it.enabled && AuthRules.serverKey(it)==first.serverKey}){"Ящик выключен или его сервер изменился."};true}},
                            onConfirmed={uids->coordinator.data {
                                if(!sameActionServer(first)){claimed.forEach{db.completeAction(it)};refresh();return@data false}
                                claimed.filter{actionMessage(it).uid in uids}.forEach{a->db.finishAction(a,remove=setOf(a.messageId))}
                                coordinator.invalidate(account.id);refresh();true
                            }})
                        // The acknowledged move is durable; a failed follow-up refresh must
                        // never repeat it. Folder/inbox sync records its own errors and retries.
                        try {
                            if(coordinator.data{sameActionServer(first)}) {
                                if(role==FolderRole.INBOX)sync(account.id,warmBodies=false)
                                else {discoverFolders(account.id);if(target.isNotBlank())syncFolder(account.id,target)}
                            }
                        }catch(e: CancellationException){throw e}catch(_: Exception){FolderWorker.enqueue(context)}
                    }
                    ActionKind.SEND -> {
                        val source=actionMessage(first)
                        runInterruptible{gateway.send(account,password,source.draft())}
                        coordinator.data {
                            if(db.accounts().none{it.id==account.id})return@data
                            val sent=source.copy(localFolder="sent",delivery="accepted",pendingDelete=false)
                            val following=mutableListOf(actionFor(sent,ActionKind.APPEND_SENT))
                            trustedOrigin(first)?.let{following+=actionFor(it,ActionKind.REMOVE_DRAFT)}
                            val original=source.responseTo?.let{db.message(it)}
                            val marked=ResponseMarkers.accepted(original,source.responseKind)
                            marked?.let{m->ResponseMarkers.serverAction(m,source.responseKind)?.let{following+=actionFor(m,it)}}
                            if(db.finishAction(first,items=listOfNotNull(sent,marked),enqueue=following) && marked!=null)flagsChanged(listOf(marked.id))
                            refresh()
                        }
                    }
                    ActionKind.APPEND_DRAFT,ActionKind.APPEND_SENT -> {
                        val source=actionMessage(first)
                        val live=coordinator.data{db.message(source.id)}
                        val editing=source.draft().copy(forwarding=live?.forwarding?:source.forwarding)
                        require(editing.resourcesReady()){"Вложения черновика ещё подготавливаются. Сохраните черновик после завершения подготовки."}
                        val draft=ForwardRules.sending(editing)
                        val role=if(first.kind==ActionKind.APPEND_DRAFT)FolderRole.DRAFTS else FolderRole.SENT
                        val copy=runInterruptible{gateway.append(account,password,draft,role,first.revision,trustedOrigin(first))}
                        coordinator.data {
                            if(!sameActionServer(first)){db.completeAction(first);refresh();return@data}
                            val copyId=FolderRules.id(account.id,copy.folder,copy.validity,copy.uid)
                            val remote=source.copy(id=if(role==FolderRole.SENT)source.id else copyId,uid=copy.uid,validity=copy.validity,remoteFolder=copy.folder,
                                localFolder=FolderRules.local(role),delivery=if(role==FolderRole.SENT)"accepted" else "",sourceDraftId=null,outgoing=draft.attachments,forwarding=null)
                            val local=db.message(source.id)
                            if(role==FolderRole.SENT) {
                                val updated=remote.copy(unread=local?.unread?:remote.unread,starred=local?.starred?:remote.starred)
                                val flags=if(updated.unread || updated.starred)listOf(actionFor(updated,ActionKind.FLAGS,unread=updated.unread,starred=updated.starred))else emptyList()
                                if(!db.finishAction(first,items=listOf(updated),enqueue=flags) && (local==null || local.pendingDelete)) {
                                    // A delete can race an accepted APPEND; clean up the acknowledged copy.
                                    db.putAction(actionFor(remote,ActionKind.TRASH))
                                }
                            }
                            else if(local?.localFolder=="drafts") {
                                // Changing sender may move a server draft between accounts.
                                // Remove the old copy only after the new APPEND is confirmed,
                                // and only in its captured account/server namespace.
                                val cleanup=trustedOrigin(first)?.takeIf{it.accountId!=account.id}?.let{listOf(actionFor(it,ActionKind.REMOVE_DRAFT))}.orEmpty()
                                db.finishAction(first,items=listOf(remote,local.copy(sourceDraftId=copyId)),enqueue=cleanup)
                            }
                            else {
                                val cleanup=actionFor(remote,ActionKind.REMOVE_DRAFT)
                                if(!db.finishAction(first,enqueue=listOf(cleanup)))db.putAction(cleanup)
                            }
                            db.putFolders(listOf(db.folders().find{it.accountId==account.id && it.path==copy.folder}?.copy(validity=copy.validity)?:ServerFolder(account.id,copy.folder,copy.folder.substringAfterLast('/'),role,validity=copy.validity)))
                            coordinator.invalidate(account.id);refresh()
                        }
                    }
                    ActionKind.REMOVE_DRAFT -> {
                        val m=actionMessage(first)
                        runInterruptible{gateway.removeDraft(account,password,m)}
                        coordinator.data{if(sameActionServer(first))db.finishAction(first,remove=setOf(m.id))else db.completeAction(first);coordinator.invalidate(account.id);refresh()}
                    }
                }
            }catch(e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable){coordinator.data{claimed.forEach{a->
                    val updated=a.copy(status=if(a.kind==ActionKind.SEND)"uncertain" else "pending",error=if(a.kind==ActionKind.SEND)"Отправка прервана. Проверьте доставку перед повтором." else "",lease="")
                    db.replaceAction(a,updated);if(a.kind==ActionKind.SEND)db.message(a.messageId)?.let{db.putMessage(it.copy(delivery="uncertain"))}
                };refresh()}}
                throw e
            }catch(e: Exception) {
                coordinator.data {
                    val causes=mailErrorChain(e)
                    val uncertain=causes.any{it is DeliveryUncertainException}
                    val auth=causes.any{it is GoogleMailAuthException || it is jakarta.mail.AuthenticationFailedException}
                    val permanent=e is IllegalArgumentException || e is IllegalStateException && !auth && !e.message.orEmpty().contains("подготавливаются") || e is jakarta.mail.MessagingException && causes.none{it is java.io.IOException}
                    claimed.forEach{a->
                        val status=when{uncertain && a.kind==ActionKind.SEND->"uncertain";auth->"auth";permanent->"failed";else->"pending"}
                        val updated=a.copy(status=status,attempt=a.attempt+1,nextAttempt=ActionRules.retryAt(System.currentTimeMillis(),a.attempt),error=friendlyError(e),lease="")
                        if(db.replaceAction(a,updated)) {
                            val current=db.message(a.messageId)
                            if(a.kind==ActionKind.SEND && current!=null)db.putMessage(current.copy(delivery=if(status=="uncertain")"uncertain" else "queued"))
                            if(status=="failed" && current!=null && sameActionServer(a)) {
                                val original=actionMessage(a)
                                if(a.kind in setOf(ActionKind.TRASH,ActionKind.SPAM,ActionKind.RESTORE_INBOX))spamCounts(listOf(current),1)
                                db.putMessage(current.copy(pendingDelete=false,unread=if(a.kind==ActionKind.FLAGS && a.unread!=null)original.unread else current.unread,
                                    starred=if(a.kind==ActionKind.FLAGS && a.starred!=null)original.starred else current.starred))
                            }
                        }
                    }
                    refresh()
                }
            }
        }
    }}
    suspend fun retryAction(id: String,confirmUncertain: Boolean=false)=withContext(Dispatchers.IO) {
        coordinator.data {
            val a=db.action(id)?:return@data
            require(a.status!="uncertain" || confirmUncertain){"Сначала проверьте доставку и подтвердите повторную отправку."}
            require(a.status !in setOf("running","sending")){"Действие уже выполняется."}
            if(!a.active && a.kind in setOf(ActionKind.TRASH,ActionKind.SPAM,ActionKind.RESTORE_INBOX) && sameActionServer(a))spamCounts(listOfNotNull(db.message(a.messageId)),-1)
            db.putAction(a.copy(status="pending",nextAttempt=0,error="",lease=""))
            if(a.kind==ActionKind.SEND)db.message(a.messageId)?.let{db.putMessage(it.copy(delivery="queued"))}
            refresh()
        };wakeActions()
    }
    suspend fun cancelAction(id: String)=withContext(Dispatchers.IO) {
        coordinator.data {
            val a=db.action(id)?:return@data
            require(a.status !in setOf("running","sending")){"Действие уже выполняется. Дождитесь результата."}
            val current=db.message(a.messageId)?.takeIf{a.kind==ActionKind.SEND || sameActionServer(a)};val original=actionMessage(a)
            val restored=when(a.kind) {
                ActionKind.SEND->current?.copy(localFolder="drafts",delivery=if(a.status=="uncertain")"uncertain" else "")
                ActionKind.TRASH,ActionKind.REMOVE_DRAFT,ActionKind.SPAM,ActionKind.RESTORE_INBOX->current?.copy(pendingDelete=false)
                ActionKind.FLAGS->current?.copy(unread=if(a.unread!=null)original.unread else current.unread,starred=if(a.starred!=null)original.starred else current.starred)
                else->null
            }
            if(db.finishAction(a,items=listOfNotNull(restored)) && a.active && a.kind in setOf(ActionKind.TRASH,ActionKind.SPAM,ActionKind.RESTORE_INBOX))spamCounts(listOfNotNull(restored),1)
            refresh()
        }
    }
    private data class AttachmentRequest(val account: MailAccount,val version: Long)
    private suspend fun cachedAttachment(message: MailItem,file: Attachment,limit: Long?=null): java.io.File=withContext(Dispatchers.IO){
        attachmentLocks.computeIfAbsent("${message.id}|${file.path}"){Mutex()}.withLock{
            coordinator.request(prepare={
                db.accounts().find{it.id==message.accountId}?.let{AttachmentRequest(it,coordinator.version(it.id))}
            },fetch={request->runInterruptible{
                val a=request.account
                attachmentCache.prepare(message,file){output->require(a.enabled){"Включите ящик для загрузки вложения."};gateway.attachment(a,vault.open(a.id,a.secret),message,file,if(limit==null)output else BoundedMailOutput(output,limit))}
            }},commit={request,cached->
                require(coordinator.version(request.account.id)==request.version && state.value.accounts.any{it.id==request.account.id}){"Ящик изменён или удалён. Повторите открытие вложения."}
                StorageWorker.enqueue(context);cached
            })?:error("Ящик не найден.")
        }
    }
    suspend fun saveOriginal(message: MailItem,uri: Uri)=withContext(Dispatchers.IO){
        val temporary=java.io.File.createTempFile("original-",".eml",context.cacheDir)
        try {
            require(message.uid>0 && message.validity>0 && message.remoteFolder.isNotBlank()){"У письма нет оригинала на сервере."}
            coordinator.request(prepare={
                db.accounts().find{it.id==message.accountId}?.let{AttachmentRequest(it,coordinator.version(it.id))}
            },fetch={request->runInterruptible {
                val a=request.account
                require(a.enabled){"Включите ящик для сохранения оригинала."}
                temporary.outputStream().use{gateway.original(a,vault.open(a.id,a.secret),message,it)}
            }},commit={request,_->
                require(coordinator.version(request.account.id)==request.version){"Ящик изменён. Повторите сохранение."}
            })?:error("Ящик не найден.")
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri,"wt")?.use{output->temporary.inputStream().use{it.copyTo(output,64*1024)}}
                ?:error("Не удалось создать файл.")
        } catch(e: Exception) {
            runCatching{android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}
            throw e
        } finally { temporary.delete() }
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
    suspend fun inlineImage(message: MailItem,file: Attachment): OpenedAttachment=withContext(Dispatchers.IO){
        require(file.contentId.isNotBlank() && file.mime.startsWith("image/",true) && message.attachments.any{it.path==file.path && it.contentId==file.contentId}){"Изображение не найдено в письме."}
        message.outgoing.firstOrNull{it.uri==file.path && it.contentId.isNotBlank()}?.let{return@withContext OpenedAttachment(it.uri,it.name,it.mime)}
        require(file.size.toLong()<=MailImageLimits.MAX_BYTES*2){"Изображение слишком большое для просмотра в письме."}
        attachmentCache.opened(cachedAttachment(message,file,MailImageLimits.MAX_BYTES),file)
    }
    suspend fun thumbnailImage(message: MailItem,file: Attachment): OpenedAttachment=withContext(Dispatchers.IO){
        require(AttachmentRules.isImage(file.name,file.mime) && message.attachments.any{it.path==file.path}){"Изображение не найдено в письме."}
        require(file.size>=0 && file.size.toLong()<=AttachmentRules.THUMBNAIL_MAX_BYTES){"Изображение слишком большое для миниатюры."}
        attachmentCache.opened(cachedAttachment(message,file,AttachmentRules.THUMBNAIL_MAX_BYTES),file)
    }
    private fun ComposeDraft.toMessage(a: MailAccount,folder: String): MailItem {
        val content=ComposeContent.render(this)
        val ownId=MessageIdentity.forDraft(id,internetMessageId)
        return MailItem(id,accountId,sender=a.senderName,senderEmail=a.email,recipients=to,cc=cc,bcc=bcc,
            subject=subject,body=content.plain,htmlBody=content.html,contentVersion=MailContent.FORMAT_VERSION,
            date=if(folder in setOf("outbox","sent") && timestamp>0)timestamp else System.currentTimeMillis(),localFolder=folder,outgoing=attachments,
            forwarding=forwarding,internetMessageId=ownId,sourceDraftId=sourceDraftId,responseTo=responseTo,responseKind=responseKind,
            attachments=attachments.filter{it.contentId.isNotBlank() && HtmlMail.cid(it.contentId) in content.inlineIds}.map{file->
                Attachment(file.uri,file.name,file.mime,file.size.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),content.inlineIds.getValue(HtmlMail.cid(file.contentId)))},
            composer=ComposeCodec.snapshot(this).copy(resourceAliases=content.inlineIds.entries.associate{it.value to it.key}),headers=MessageThread.outgoing(this))
    }
}
