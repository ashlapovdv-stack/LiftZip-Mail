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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MailSnapshot(val accounts: List<MailAccount> = emptyList(), val messages: List<MailItem> = emptyList(), val history: Map<String,InboxHistory> = emptyMap(), val defaultAccountId: String? = null,
    val poll: PollSettings = PollSettings(), val historyDownload: HistoryDownload = HistoryDownload(), val checks: Map<String,SyncCheck> = emptyMap())
data class SyncResult(val newUnread: Int, val errors: List<String>)

class MailApplication : Application() {
    val repository by lazy { MailRepository(this) }
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
    private val parallelChecks=Semaphore(3)
    private val flagVersions=mutableMapOf<String,Long>()
    private var nextFlagVersion=0L
    private val state=MutableStateFlow(MailSnapshot())
    val snapshot=state.asStateFlow()
    private fun refresh(arrived: Set<String> = emptySet(),messages: List<MailItem>?=null) {
        val accounts=db.accounts()
        state.value=MailSnapshot(accounts,messages?:db.messages(),accounts.associate { a->
            a.id to InboxHistory(if(syncPrefs.contains("count:${a.id}"))syncPrefs.getInt("count:${a.id}",0) else null,syncPrefs.getBoolean("older:${a.id}",true))
        },settings.getString("default-sender",null)?.takeIf{id->accounts.any{it.id==id}},
            PollSettings.normalized(settings.getInt("foreground-minutes",1),settings.getInt("background-minutes",15)),readHistory(),
            accounts.associate{it.id to SyncCheck(syncPrefs.getLong("checked:${it.id}",0),syncPrefs.getString("error:${it.id}","").orEmpty(),syncPrefs.getLong("duration:${it.id}",0))})
        notifications.reconcile(SyncRules.activeMessages(state.value.messages,accounts,null),arrived)
        notifications.history(state.value)
    }
    suspend fun load()=withContext(Dispatchers.IO){coordinator.data{refresh()}}
    private data class Request(val account: MailAccount,val version: Long,val cached: List<MailItem>,val flags: Map<String,Long>,val initialized: Boolean)
    private data class CheckReply(val inbox: InboxPage?=null,val unread: UnreadPage?=null)
    private data class HistoryRequest(val request: Request,val missing: List<MailItem>)
    private data class HistoryReply(val page: InboxPage?=null,val contents: ContentBatch?=null)
    private fun capture(id: String): Request? {
        val a=state.value.accounts.find{it.id==id && it.enabled}?:return null
        return Request(a,coordinator.version(id),state.value.messages.filter{it.accountId==id && SyncRules.remoteInbox(it)},flagVersions.toMap(),syncPrefs.getBoolean(id,false))
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
    suspend fun message(id: String): MailItem?=withContext(Dispatchers.IO){
        val cached=db.message(id)?:return@withContext null
        if(cached.contentCached)return@withContext cached
        coordinator.request(prepare={capture(cached.accountId)?.let{request->request to (db.message(id)?:cached)}},fetch={(request,m)->
            if(m.contentCached)m else runInterruptible{gateway.content(request.account,vault.open(request.account.id,request.account.secret),m)}
        },commit={(request,_),full->
            require(current(request)){"Ящик приостановлен или его настройки изменились."}
            val now=state.value.messages.find{it.id==id}?:return@request null
            val result=full.copy(unread=now.unread,starred=now.starred,localFolder=now.localFolder)
            db.putMessage(result);refresh(messages=state.value.messages.map{if(it.id==id)MailContent.preview(result)else it});result
        })?:error("Включите ящик в меню, чтобы загрузить текст письма.")
    }
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
        if(reset)syncPrefs.edit().remove(old.id).remove("count:${old.id}").remove("older:${old.id}").apply()
        coordinator.invalidate(old.id)
        db.updateAccount(account.copy(secret=vault.seal(old.id,secret),enabled=old.enabled),reset)
        val history=readHistory()
        if(reset && old.id in history.accountIds && history.active)writeHistory(history.copy(status="paused",error="Настройки ящика изменены. Продолжите загрузку истории."))
        refresh()
    }}
    suspend fun removeAccount(id: String)=withContext(Dispatchers.IO){coordinator.data{
        db.messages().filter{it.accountId==id}.forEach{forwarded.remove(it.id)}
        coordinator.invalidate(id);db.removeAccount(id);attachmentCache.removeAccount(id)
        if(settings.getString("default-sender",null)==id)settings.edit().remove("default-sender").apply()
        syncPrefs.edit().remove(id).remove("count:$id").remove("older:$id").remove("checked:$id").remove("error:$id").apply()
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
    suspend fun setPoll(poll: PollSettings)=withContext(Dispatchers.IO){coordinator.data{
        val p=PollSettings.normalized(poll.foregroundMinutes,poll.backgroundMinutes)
        settings.edit().putInt("foreground-minutes",p.foregroundMinutes).putInt("background-minutes",p.backgroundMinutes).apply();refresh()
    }}
    suspend fun sync(accountId: String?=null): SyncResult=checkAccounts(accountId,false)
    suspend fun syncUnread(accountId: String?): SyncResult=checkAccounts(accountId,true)
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
                val arrived=if(page==null)emptySet() else SyncRules.newUnreadIds(captured.cached.filter{it.localFolder=="inbox"},page.messages,captured.initialized)
                val validity=page?.validity?:unreadPage!!.validity
                val total=page?.history?.total?:unreadPage?.total
                val previousEpoch=now.firstOrNull()?.validity
                rememberEpoch(id,validity,total)
                val edit=syncPrefs.edit().putInt("count:$id",total?:merged.size).putLong("checked:$id",System.currentTimeMillis())
                    .putLong("duration:$id",(System.nanoTime()-started)/1_000_000).remove("error:$id")
                if(page!=null) {
                    val oldIds=captured.cached.filter{it.localFolder=="inbox"}.map{it.id}.toSet()
                    val expanded=now.any{it.localFolder=="inbox" && it.id !in oldIds}
                    edit.putBoolean(id,true).putBoolean("older:$id",if(expanded && previousEpoch==validity)state.value.history[id]?.hasOlder?:page.history.hasOlder else page.history.hasOlder)
                }else if(previousEpoch!=null && previousEpoch!=validity)edit.putBoolean(id,false).putBoolean("older:$id",true)
                edit.apply();saveRemote(id,now,merged,arrived);SyncResult(arrived.size,emptyList())
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
                    db.putMessages(updated);flagsChanged(successful);changed+=successful
                    refresh(messages=state.value.messages.map{if(it.id in successful)it.copy(unread=unread)else it})
                    if(successful.size!=group.size)errors+="${request.account.name}: некоторые письма перемещены или удалены. Обновите список."
                }
            }catch(e: CancellationException){throw e}catch(e: Exception){errors+="${request.account.name}: ${friendlyError(e)}"}
        }
        FlagChange(changed,errors.distinct())
    }
    private fun readHistory(): HistoryDownload=runCatching{
        val j=JSONObject(settings.getString("history-download","{}").orEmpty())
        val ids=j.optJSONArray("accounts")?:JSONArray()
        HistoryDownload(j.optString("id"),List(ids.length()){ids.getString(it)},j.optString("status","idle"),j.optString("error"))
    }.getOrDefault(HistoryDownload())
    private fun writeHistory(job: HistoryDownload) {
        settings.edit().putString("history-download",JSONObject().put("id",job.id).put("accounts",JSONArray(job.accountIds))
            .put("status",job.status).put("error",job.error).toString()).apply()
    }
    suspend fun startHistory(ids: List<String>): String=withContext(Dispatchers.IO){coordinator.data{
        val available=db.accounts().filter{it.enabled}.map{it.id}.toSet()
        val selected=ids.distinct().filter{it in available}
        require(selected.isNotEmpty()){"Выберите хотя бы один ящик."}
        val job=HistoryDownload(UUID.randomUUID().toString(),selected,"running")
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
                val history=accounts.associate{it.id to InboxHistory(hasOlder=SyncRules.needsHistory(it.id,state.value.messages,state.value.history[it.id]))}
                val accountId=SyncRules.nextHistoryAccount(job,accounts,history,skipped)
                if(accountId==null) {
                    val retry=SyncRules.nextHistoryAccount(job,accounts,history,emptySet())!=null
                    val disabled=accounts.any{!it.enabled && it.id in job.accountIds && history[it.id]?.hasOlder==true}
                    writeHistory(job.copy(status=if(retry)"waiting" else if(disabled)"paused" else "done",error=if(retry)job.error else if(disabled)"Включите приостановленные ящики в меню и продолжите загрузку." else ""))
                    refresh(messages=state.value.messages);stopped=HistoryStep(retry=retry);null
                }else capture(accountId)?.let{request->HistoryRequest(request,request.cached.filter{it.localFolder=="inbox" && !it.contentCached}.take(10)).also{captured=it}}
            },fetch={work->
                val coroutine=currentCoroutineContext();val a=work.request.account
                runInterruptible(Dispatchers.IO){
                    if(work.missing.isNotEmpty())HistoryReply(contents=gateway.contents(a,vault.open(a.id,a.secret),work.missing){coroutine.ensureActive()})
                    else HistoryReply(page=gateway.inbox(a,vault.open(a.id,a.secret),work.request.cached,loadOlder=true,pageSize=10,checkCancelled={coroutine.ensureActive()}))
                }
            },commit={work,response->
                val job=readHistory();val request=work.request;val a=request.account
                if(!current(request) || job.id!=id || !job.active)return@request HistoryStep(more=true)
                val now=state.value.messages.filter{it.accountId==a.id && SyncRules.remoteInbox(it)}
                val page=response.page
                val merged=if(page!=null)SyncRules.historyPage(now,page,protected(request))else SyncRules.contentBatch(now,response.contents!!)
                if(page!=null){rememberEpoch(a.id,page.validity,page.history.total);syncPrefs.edit().putBoolean(a.id,true).putInt("count:${a.id}",page.history.total?:merged.size).putBoolean("older:${a.id}",page.history.hasOlder).apply()}
                if(response.contents?.missingIds?.isNotEmpty()==true)coordinator.invalidate(a.id)
                writeHistory(job.copy(status="running",error=if(skipped.isEmpty())"" else job.error))
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
    suspend fun saveDraft(draft: ComposeDraft)=withContext(Dispatchers.IO){coordinator.data{
        val a=db.accounts().find { it.id==draft.accountId }?:error("Выберите ящик отправителя.")
        db.putMessage(draft.toMessage(a,"drafts").copy(delivery=if(draft.previousDeliveryUncertain)"uncertain" else ""));refresh()
    }}
    suspend fun deleteDraft(id: String)=withContext(Dispatchers.IO){coordinator.data{db.removeMessage(id);forwarded.remove(id);refresh()}}
    suspend fun forward(message: MailItem,senderId: String): ComposeDraft=withContext(Dispatchers.IO){coordinator.data{
        val accounts=db.accounts()
        val sender=accounts.find{it.id==senderId}?:error("Ящик отправителя не найден.")
        require(sender.enabled){"Включите ящик отправителя в меню."}
        val origin=accounts.find{it.id==message.accountId}?:error("Исходный ящик не найден.")
        val draft=ComposeDraft(accountId=sender.id,subject=if(message.subject.startsWith("Fwd:",true))message.subject else "Fwd: ${message.subject}",
            body="\n\n──── Пересланное письмо ────\nОт: ${message.sender} <${message.senderEmail}>\nКому: ${message.recipients}\nТема: ${message.subject}\n\n${message.body}")
        val sources=message.attachments.map { attachment->
            ForwardSource(attachment.name,attachment.mime,attachment.size.toLong()) {
                attachmentCache.prepare(message,attachment){output->require(origin.enabled){"Включите исходный ящик для загрузки вложений."};gateway.attachment(origin,vault.open(origin.id,origin.secret),message,attachment,output)}.inputStream()
            }
        }+message.outgoing.map { file->
            ForwardSource(file.name,file.mime,file.size) {context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось открыть ${file.name}")}
        }
        val ready=draft.copy(attachments=forwarded.prepare(draft.id,sources))
        db.putMessage(ready.toMessage(sender,"drafts"));refresh();ready
    }}
    suspend fun send(draft: ComposeDraft)=withContext(Dispatchers.IO){coordinator.data{
        MailRules.validateDraft(draft)?.let { error(it) }
        val a=db.accounts().find { it.id==draft.accountId }?:error("Ящик отправителя не найден.")
        require(a.enabled){"Включите ящик отправителя в меню."}
        db.putMessage(draft.toMessage(a,"drafts").copy(delivery="sending"));refresh()
        try {
            gateway.send(a,vault.open(a.id,a.secret),draft)
            db.putMessage(draft.toMessage(a,"sent").copy(delivery="accepted"));refresh()
        } catch(e: Exception) {
            db.putMessage(draft.toMessage(a,"drafts").copy(delivery=if(e is DeliveryUncertainException)"uncertain" else "failed"));refresh();throw e
        }
    }}
    suspend fun download(message: MailItem,file: Attachment,uri: Uri)=withContext(Dispatchers.IO){coordinator.data{
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        try {
            val cached=attachmentCache.prepare(message,file){output->require(a.enabled){"Включите ящик для загрузки вложения."};gateway.attachment(a,vault.open(a.id,a.secret),message,file,output)}
            context.contentResolver.openOutputStream(uri)?.use { output->cached.inputStream().use{it.copyTo(output)} }?:error("Не удалось создать файл.")
        }catch(e: Exception){runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri) };throw e}
    }}
    suspend fun openAttachment(message: MailItem,file: Attachment): OpenedAttachment=withContext(Dispatchers.IO){coordinator.data{
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        val cached=attachmentCache.prepare(message,file){output->require(a.enabled){"Включите ящик для загрузки вложения."};gateway.attachment(a,vault.open(a.id,a.secret),message,file,output)}
        attachmentCache.opened(cached,file)
    }}
    private fun ComposeDraft.toMessage(a: MailAccount,folder: String)=MailItem(id,accountId,sender=a.senderName,senderEmail=a.email,recipients=to,
        subject=subject,body=body,date=System.currentTimeMillis(),localFolder=folder,outgoing=attachments)
}

