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

data class MailSnapshot(val accounts: List<MailAccount> = emptyList(), val messages: List<MailItem> = emptyList(), val history: Map<String,InboxHistory> = emptyMap(), val defaultAccountId: String? = null)
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
    private val settings=context.getSharedPreferences("mail-settings",Context.MODE_PRIVATE)
    private val syncPrefs=context.getSharedPreferences("sync-baseline",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private val state=MutableStateFlow(MailSnapshot())
    val snapshot=state.asStateFlow()
    private fun refresh() {
        val accounts=db.accounts()
        state.value=MailSnapshot(accounts,db.messages(),accounts.associate { a->
            a.id to InboxHistory(if(syncPrefs.contains("count:${a.id}"))syncPrefs.getInt("count:${a.id}",0) else null,syncPrefs.getBoolean("older:${a.id}",true))
        },settings.getString("default-sender",null)?.takeIf{id->accounts.any{it.id==id}})
    }
    suspend fun load()=withContext(Dispatchers.IO){mutex.withLock{refresh()}}
    suspend fun addAccount(account: MailAccount,password: String)=withContext(Dispatchers.IO){mutex.withLock {
        require(db.accounts().none { it.email.equals(account.email,true) && it.imapHost.equals(account.imapHost,true) }) { "Этот ящик уже добавлен." }
        gateway.verify(account,password)
        // Persist only after both incoming and outgoing authentication succeed.
        val encrypted=account.copy(secret=vault.seal(account.id,password))
        db.putAccount(encrypted)
        refresh()
    }}
    suspend fun removeAccount(id: String)=withContext(Dispatchers.IO){mutex.withLock{
        db.messages().filter{it.accountId==id}.forEach{forwarded.remove(it.id)}
        db.removeAccount(id);attachmentCache.removeAccount(id)
        if(settings.getString("default-sender",null)==id)settings.edit().remove("default-sender").apply()
        syncPrefs.edit().remove(id).remove("count:$id").remove("older:$id").apply();refresh()
    }}
    suspend fun setDefaultAccount(id: String?)=withContext(Dispatchers.IO){mutex.withLock{
        require(id==null || db.accounts().any{it.id==id}){"Ящик не найден."}
        settings.edit().putString("default-sender",id).apply();refresh()
    }}
    suspend fun sync(): SyncResult=updateInboxes(null,false)
    suspend fun loadOlder(accountId: String?): SyncResult=updateInboxes(accountId,true)
    private suspend fun updateInboxes(accountId: String?,loadOlder: Boolean): SyncResult=withContext(Dispatchers.IO){mutex.withLock {
        var newUnread=0
        val errors=mutableListOf<String>()
        db.accounts().filter{accountId==null || it.id==accountId}.forEach { a->
            if(loadOlder && !syncPrefs.getBoolean("older:${a.id}",true))return@forEach
            try {
                val old=db.messages().filter { it.accountId==a.id && it.localFolder=="inbox" }
                val page=gateway.inbox(a,vault.open(a.id,a.secret),old,loadOlder)
                if(!loadOlder)newUnread+=MailRules.newUnread(old,page.messages,syncPrefs.getBoolean(a.id,false))
                db.replaceInbox(a.id,page.messages)
                syncPrefs.edit().putBoolean(a.id,true).putInt("count:${a.id}",page.history.total?:page.messages.size).putBoolean("older:${a.id}",page.history.hasOlder).apply()
            } catch(e: CancellationException){throw e}
            catch(e: Exception){errors+="${a.name}: ${friendlyError(e)}"}
        }
        refresh();SyncResult(newUnread,errors)
    }}
    suspend fun changeFlags(message: MailItem,unread: Boolean?=null,starred: Boolean?=null)=withContext(Dispatchers.IO){mutex.withLock {
        val a=db.accounts().find { it.id==message.accountId }?:error("Ящик не найден.")
        if(message.uid>0)gateway.flags(a,vault.open(a.id,a.secret),message,seen=unread?.not(),starred=starred)
        val current=db.messages().find { it.id==message.id }?:return@withLock
        db.putMessage(current.copy(unread=unread?:current.unread,starred=starred?:current.starred));refresh()
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

