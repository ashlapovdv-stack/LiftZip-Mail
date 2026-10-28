package ru.liftzip.mail

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val mutex=Mutex()
    private val state=MutableStateFlow(MailSnapshot())
    val snapshot=state.asStateFlow()
    private fun refresh(arrived: Set<String> = emptySet(),messages: List<MailItem>?=null) {
        val accounts=db.accounts()
        state.value=MailSnapshot(accounts,messages?:db.messages(),accounts.associate { a->
            a.id to InboxHistory(if(syncPrefs.contains("count:${a.id}"))syncPrefs.getInt("count:${a.id}",0) else null,syncPrefs.getBoolean("older:${a.id}",true))
        },settings.getString("default-sender",null)?.takeIf{id->accounts.any{it.id==id}},
            PollSettings.normalized(settings.getInt("foreground-minutes",1),settings.getInt("background-minutes",15)),readHistory(),
            accounts.associate{it.id to SyncCheck(syncPrefs.getLong("checked:${it.id}",0),syncPrefs.getString("error:${it.id}","").orEmpty())})
        notifications.reconcile(state.value.messages,arrived)
        notifications.history(state.value)
    }
    suspend fun load()=withContext(Dispatchers.IO){mutex.withLock{refresh()}}
    suspend fun message(id: String): MailItem?=withContext(Dispatchers.IO){
        val cached=db.message(id)?:return@withContext null
        if(cached.contentCached)return@withContext cached
        mutex.withLock {
            val current=db.message(id)?:return@withLock null
            if(current.contentCached)return@withLock current
            val account=db.accounts().find{it.id==current.accountId}?:error("Ящик не найден.")
            val full=gateway.content(account,vault.open(account.id,account.secret),current)
            db.putMessage(full)
            refresh(messages=state.value.messages.map{if(it.id==id)MailContent.preview(full)else it})
            full
        }
    }
    suspend fun search(query: String): Set<String> =withContext(Dispatchers.IO){
        val coroutine=currentCoroutineContext();db.search(query){coroutine.ensureActive()}
    }
    suspend fun addAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO){mutex.withLock {
        require(db.accounts().none { it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true) }) { "Этот ящик уже добавлен." }
        gateway.verify(account,password)
        // Persist only after both incoming and outgoing authentication succeed.
        val encrypted=account.copy(secret=vault.seal(account.id,password))
        db.putAccount(encrypted)
        refresh()
    }}
    suspend fun editAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO){mutex.withLock {
        val old=db.accounts().find{it.id==account.id}?:error("Ящик не найден.")
        require(db.accounts().none{it.id!=old.id && it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true)}){"Этот ящик уже добавлен."}
        require(old.email.equals(account.email,true)){"Для другого адреса добавьте отдельный ящик."}
        MailRules.validateAccount(account,password,passwordRequired=false)?.let{error(it)}
        val secret=password.ifEmpty{vault.open(old.id,old.secret)}
        gateway.verify(account,secret)
        val reset=!SyncRules.sameInbox(old,account)
        if(reset)syncPrefs.edit().remove(old.id).remove("count:${old.id}").remove("older:${old.id}").apply()
        db.updateAccount(account.copy(secret=vault.seal(old.id,secret)),reset)
        val history=readHistory()
        if(reset && old.id in history.accountIds && history.active)writeHistory(history.copy(status="paused",error="Настройки ящика изменены. Продолжите загрузку истории."))
        refresh()
    }}
    suspend fun removeAccount(id: String)=withContext(Dispatchers.IO){mutex.withLock{
        db.messages().filter{it.accountId==id}.forEach{forwarded.remove(it.id)}
        db.removeAccount(id);attachmentCache.removeAccount(id)
        if(settings.getString("default-sender",null)==id)settings.edit().remove("default-sender").apply()
        syncPrefs.edit().remove(id).remove("count:$id").remove("older:$id").remove("checked:$id").remove("error:$id").apply()
        val job=readHistory()
        if(id in job.accountIds)writeHistory(job.copy(accountIds=job.accountIds-id,status=if(job.accountIds.size==1)"done" else job.status))
        refresh()
    }}
    suspend fun setDefaultAccount(id: String?)=withContext(Dispatchers.IO){mutex.withLock{
        require(id==null || db.accounts().any{it.id==id}){"Ящик не найден."}
        settings.edit().putString("default-sender",id).apply();refresh()
    }}
    suspend fun setPoll(poll: PollSettings)=withContext(Dispatchers.IO){mutex.withLock{
        val p=PollSettings.normalized(poll.foregroundMinutes,poll.backgroundMinutes)
        settings.edit().putInt("foreground-minutes",p.foregroundMinutes).putInt("background-minutes",p.backgroundMinutes).apply();refresh()
    }}
    suspend fun sync(): SyncResult=updateInboxes(null,false)
    suspend fun syncUnread(accountId: String?): SyncResult=withContext(Dispatchers.IO){mutex.withLock{
        val errors=mutableListOf<String>()
        var messages=state.value.messages
        db.accounts().filter{accountId==null || it.id==accountId}.forEach{account->
            try {
                currentCoroutineContext().ensureActive()
                val old=messages.filter{it.accountId==account.id && SyncRules.remoteInbox(it)}
                val coroutine=currentCoroutineContext()
                val page=gateway.unread(account,vault.open(account.id,account.secret),old){coroutine.ensureActive()}
                coroutine.ensureActive()
                val merged=SyncRules.mergeUnread(old,page)
                db.applyInbox(old,merged)
                messages=messages.filterNot{it.accountId==account.id && SyncRules.remoteInbox(it)}+merged.map(MailContent::preview)
                if(old.any{it.validity!=page.validity})syncPrefs.edit().putBoolean(account.id,false).putBoolean("older:${account.id}",true).apply()
                syncPrefs.edit().putInt("count:${account.id}",page.total).putLong("checked:${account.id}",System.currentTimeMillis()).remove("error:${account.id}").apply()
                refresh(messages=messages)
            }catch(e: CancellationException){throw e}catch(e: Exception){
                val error=friendlyError(e);errors+="${account.name}: $error";syncPrefs.edit().putString("error:${account.id}",error).apply()
            }
        }
        refresh(messages=messages);SyncResult(0,errors)
    }}
    suspend fun loadOlder(accountId: String?): SyncResult=updateInboxes(accountId,true)
    private suspend fun updateInboxes(accountId: String?,loadOlder: Boolean): SyncResult=withContext(Dispatchers.IO){mutex.withLock {
        val arrived=mutableSetOf<String>()
        val errors=mutableListOf<String>()
        var messages=state.value.messages
        db.accounts().filter{accountId==null || it.id==accountId}.forEach { a->
            if(loadOlder && !syncPrefs.getBoolean("older:${a.id}",true))return@forEach
            try {
                currentCoroutineContext().ensureActive()
                val old=messages.filter { it.accountId==a.id && SyncRules.remoteInbox(it) }
                val coroutine=currentCoroutineContext()
                val page=gateway.inbox(a,vault.open(a.id,a.secret),old,loadOlder,checkCancelled={coroutine.ensureActive()})
                if(!loadOlder)arrived+=SyncRules.newUnreadIds(old.filter{it.localFolder=="inbox"},page.messages,syncPrefs.getBoolean(a.id,false))
                val merged=SyncRules.mergeWindow(old,page)
                db.applyInbox(old,merged)
                messages=messages.filterNot{it.accountId==a.id && SyncRules.remoteInbox(it)}+merged.map(MailContent::preview)
                syncPrefs.edit().putBoolean(a.id,true).putInt("count:${a.id}",page.history.total?:page.messages.size).putBoolean("older:${a.id}",page.history.hasOlder).apply()
                if(!loadOlder)syncPrefs.edit().putLong("checked:${a.id}",System.currentTimeMillis()).remove("error:${a.id}").apply()
                refresh(arrived,messages)
            } catch(e: CancellationException){throw e}
            catch(e: Exception){val error=friendlyError(e);errors+="${a.name}: $error";syncPrefs.edit().putString("error:${a.id}",error).apply()}
        }
        refresh(arrived,messages);SyncResult(arrived.size,errors)
    }}
    suspend fun changeFlags(message: MailItem,unread: Boolean?=null,starred: Boolean?=null)=withContext(Dispatchers.IO){mutex.withLock {
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        if(message.uid>0)gateway.flags(a,vault.open(a.id,a.secret),message,seen=unread?.not(),starred=starred)
        val current=state.value.messages.find { it.id==message.id }?:return@withLock
        val updated=current.copy(unread=unread?:current.unread,starred=starred?:current.starred)
        db.putMessage(updated);refresh(messages=state.value.messages.map{if(it.id==updated.id)updated else it})
    }}
    suspend fun markRead(ids: Set<String>,unread: Boolean): FlagChange=withContext(Dispatchers.IO){mutex.withLock {
        val accounts=db.accounts().associateBy{it.id}
        val requested=state.value.messages.filter{it.id in ids}
        val updated=mutableSetOf<String>()
        val errors=mutableListOf<String>()
        requested.groupBy{Triple(it.accountId,it.remoteFolder,it.validity)}.forEach{(key,group)->
            val account=accounts[key.first]?:return@forEach
            val local=group.filter{it.uid<=0}
            db.putMessages(local.map{it.copy(unread=unread)});updated+=local.map{it.id}
            group.filter{it.uid>0}.chunked(100).forEach{batch->
                try {
                    val changed=gateway.flagsMany(account,vault.open(account.id,account.secret),batch,seen=!unread)
                    val successful=batch.filter{it.uid in changed}
                    db.putMessages(successful.map{it.copy(unread=unread)});updated+=successful.map{it.id}
                    if(successful.size!=batch.size)errors+="${account.name}: некоторые письма перемещены или удалены. Обновите список."
                }catch(e: CancellationException){throw e}catch(e: Exception){errors+="${account.name}: ${friendlyError(e)}"}
            }
        }
        refresh(messages=state.value.messages.map{if(it.id in updated)it.copy(unread=unread)else it});FlagChange(updated,errors.distinct())
    }}
    private fun readHistory(): HistoryDownload=runCatching{
        val j=JSONObject(settings.getString("history-download","{}").orEmpty())
        val ids=j.optJSONArray("accounts")?:JSONArray()
        HistoryDownload(j.optString("id"),List(ids.length()){ids.getString(it)},j.optString("status","idle"),j.optString("error"))
    }.getOrDefault(HistoryDownload())
    private fun writeHistory(job: HistoryDownload) {
        settings.edit().putString("history-download",JSONObject().put("id",job.id).put("accounts",JSONArray(job.accountIds))
            .put("status",job.status).put("error",job.error).toString()).apply()
    }
    suspend fun startHistory(ids: List<String>): String=withContext(Dispatchers.IO){mutex.withLock{
        val available=db.accounts().map{it.id}.toSet()
        val selected=ids.distinct().filter{it in available}
        require(selected.isNotEmpty()){"Выберите хотя бы один ящик."}
        val job=HistoryDownload(UUID.randomUUID().toString(),selected,"running")
        writeHistory(job);refresh();job.id
    }}
    suspend fun pauseHistory()=withContext(Dispatchers.IO){mutex.withLock{
        val job=readHistory();if(job.active){writeHistory(job.copy(status="paused"));refresh()}
    }}
    suspend fun waitHistory(id: String)=withContext(Dispatchers.IO){mutex.withLock{
        val job=readHistory();if(job.id==id && job.active){writeHistory(job.copy(status="waiting"));refresh()}
    }}
    suspend fun historyStep(id: String,skipped: Set<String>): HistoryStep=withContext(Dispatchers.IO){mutex.withLock{
        val job=readHistory()
        if(job.id!=id || !job.active)return@withLock HistoryStep()
        val accounts=db.accounts()
        val history=accounts.associate{it.id to InboxHistory(hasOlder=syncPrefs.getBoolean("older:${it.id}",true))}
        val accountId=SyncRules.nextHistoryAccount(job,accounts,history,skipped)
        if(accountId==null) {
            val remaining=SyncRules.nextHistoryAccount(job,accounts,history,emptySet())!=null
            writeHistory(job.copy(status=if(remaining)"waiting" else "done",error=if(remaining)job.error else ""));refresh()
            return@withLock HistoryStep(retry=remaining)
        }
        val account=accounts.first{it.id==accountId}
        try {
            val old=state.value.messages.filter{it.accountId==accountId && SyncRules.remoteInbox(it)}
            val coroutine=currentCoroutineContext()
            val page=gateway.inbox(account,vault.open(account.id,account.secret),old,loadOlder=true,pageSize=20,checkCancelled={coroutine.ensureActive()})
            val merged=SyncRules.mergeWindow(old,page)
            db.applyInbox(old,merged)
            syncPrefs.edit().putBoolean(accountId,true).putInt("count:$accountId",page.history.total?:page.messages.size).putBoolean("older:$accountId",page.history.hasOlder).apply()
            writeHistory(job.copy(status="running",error=if(skipped.isEmpty())"" else job.error))
            refresh(messages=state.value.messages.filterNot{it.accountId==accountId && SyncRules.remoteInbox(it)}+merged.map(MailContent::preview))
            HistoryStep(more=true)
        }catch(e: CancellationException){throw e}catch(e: Exception){
            writeHistory(job.copy(error="${account.name}: ${friendlyError(e)}"));refresh();HistoryStep(more=true,failedAccount=accountId)
        }
    }}
    suspend fun saveDraft(draft: ComposeDraft)=withContext(Dispatchers.IO){mutex.withLock{
        val a=db.accounts().find { it.id==draft.accountId }?:error("Выберите ящик отправителя.")
        db.putMessage(draft.toMessage(a,"drafts").copy(delivery=if(draft.previousDeliveryUncertain)"uncertain" else ""));refresh()
    }}
    suspend fun deleteDraft(id: String)=withContext(Dispatchers.IO){mutex.withLock{db.removeMessage(id);forwarded.remove(id);refresh()}}
    suspend fun forward(message: MailItem,senderId: String): ComposeDraft=withContext(Dispatchers.IO){mutex.withLock{
        val accounts=db.accounts()
        val sender=accounts.find{it.id==senderId}?:error("Ящик отправителя не найден.")
        val origin=accounts.find{it.id==message.accountId}?:error("Исходный ящик не найден.")
        val draft=ComposeDraft(accountId=sender.id,subject=if(message.subject.startsWith("Fwd:",true))message.subject else "Fwd: ${message.subject}",
            body="\n\n──── Пересланное письмо ────\nОт: ${message.sender} <${message.senderEmail}>\nКому: ${message.recipients}\nТема: ${message.subject}\n\n${message.body}")
        val sources=message.attachments.map { attachment->
            ForwardSource(attachment.name,attachment.mime,attachment.size.toLong()) {
                attachmentCache.prepare(message,attachment){output->gateway.attachment(origin,vault.open(origin.id,origin.secret),message,attachment,output)}.inputStream()
            }
        }+message.outgoing.map { file->
            ForwardSource(file.name,file.mime,file.size) {context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось открыть ${file.name}")}
        }
        val ready=draft.copy(attachments=forwarded.prepare(draft.id,sources))
        db.putMessage(ready.toMessage(sender,"drafts"));refresh();ready
    }}
    suspend fun send(draft: ComposeDraft)=withContext(Dispatchers.IO){mutex.withLock{
        MailRules.validateDraft(draft)?.let { error(it) }
        val a=db.accounts().find { it.id==draft.accountId }?:error("Ящик отправителя не найден.")
        db.putMessage(draft.toMessage(a,"drafts").copy(delivery="sending"));refresh()
        try {
            gateway.send(a,vault.open(a.id,a.secret),draft)
            db.putMessage(draft.toMessage(a,"sent").copy(delivery="accepted"));refresh()
        } catch(e: Exception) {
            db.putMessage(draft.toMessage(a,"drafts").copy(delivery=if(e is DeliveryUncertainException)"uncertain" else "failed"));refresh();throw e
        }
    }}
    suspend fun download(message: MailItem,file: Attachment,uri: Uri)=withContext(Dispatchers.IO){mutex.withLock{
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        try {
            val cached=attachmentCache.prepare(message,file){output->gateway.attachment(a,vault.open(a.id,a.secret),message,file,output)}
            context.contentResolver.openOutputStream(uri)?.use { output->cached.inputStream().use{it.copyTo(output)} }?:error("Не удалось создать файл.")
        }catch(e: Exception){runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri) };throw e}
    }}
    suspend fun openAttachment(message: MailItem,file: Attachment): OpenedAttachment=withContext(Dispatchers.IO){mutex.withLock{
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        val cached=attachmentCache.prepare(message,file){output->gateway.attachment(a,vault.open(a.id,a.secret),message,file,output)}
        attachmentCache.opened(cached,file)
    }}
    private fun ComposeDraft.toMessage(a: MailAccount,folder: String)=MailItem(id,accountId,sender=a.senderName,senderEmail=a.email,recipients=to,
        subject=subject,body=body,date=System.currentTimeMillis(),localFolder=folder,outgoing=attachments)
}

