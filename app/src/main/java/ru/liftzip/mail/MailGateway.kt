package ru.liftzip.mail

import android.content.Context
import android.net.Uri
import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.event.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.MessageIDTerm
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.AndTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class DeliveryUncertainException : Exception("Сервер не подтвердил результат отправки. Проверьте почту получателя или отправленные на сервере перед повторной отправкой.")
class ServerCopyUnconfirmedException: java.io.IOException("Сервер пока не подтвердил сохранение копии. Проверка будет повторена без повторной отправки письма.")
class MailConnectionException(val stage: String, val host: String, val port: Int, cause: Exception) : Exception(cause)

class MailGateway(private val context: Context,private val sslSocketFactory: javax.net.ssl.SSLSocketFactory?=null) {
    private fun session(a: MailAccount,fast: Boolean=false,watchFactory: javax.net.ssl.SSLSocketFactory?=null): Session {
        if(a.authKind==AuthKind.GOOGLE)AuthRules.validateGoogle(a)?.let{error(it)}
        val imap=if(a.imapStartTls)"imap" else "imaps"
        val p=Properties().apply {
            setProperty("mail.$imap.connectiontimeout",if(fast)"8000" else "15000")
            setProperty("mail.$imap.timeout",if(fast)"10000" else "20000")
            setProperty("mail.$imap.ssl.checkserveridentity","true")
            (watchFactory?:sslSocketFactory)?.let{put("mail.$imap.ssl.socketFactory",it)}
            setProperty("mail.$imap.peek","true")
            setProperty("mail.$imap.partialfetch","true")
            setProperty("mail.$imap.fetchsize","65536")
            if(a.imapStartTls){setProperty("mail.imap.starttls.enable","true");setProperty("mail.imap.starttls.required","true")}
            setProperty("mail.smtp.auth","true")
            if(a.authKind==AuthKind.GOOGLE) {
                setProperty("mail.$imap.auth.mechanisms","XOAUTH2")
                setProperty("mail.$imap.auth.xoauth2.disable","false")
                setProperty("mail.smtp.auth.mechanisms","XOAUTH2")
                setProperty("mail.smtp.auth.xoauth2.disable","false")
            }
            setProperty("mail.smtp.connectiontimeout","15000")
            setProperty("mail.smtp.timeout","30000")
            setProperty("mail.smtp.writetimeout","30000")
            setProperty("mail.smtp.ssl.checkserveridentity","true")
            setProperty("mail.smtp.sendpartial","false")
            if(a.smtpStartTls){setProperty("mail.smtp.starttls.enable","true");setProperty("mail.smtp.starttls.required","true")}
            else setProperty("mail.smtp.ssl.enable","true")
        }
        return Session.getInstance(p).apply { debug=false }
    }
    private fun store(a: MailAccount,password: String,fast: Boolean=false): Store = session(a,fast).getStore(if(a.imapStartTls)"imap" else "imaps").apply { GoogleMailAuth.withCredential(context,a,password){connect(a.imapHost,a.imapPort,a.username,it)} }
    suspend fun watchInbox(a: MailAccount,password: String,onConnected: (Boolean)->Unit,onChanged: ()->Unit)=withContext(Dispatchers.IO) {
        val reader=IdleConnection(sslSocketFactory?:javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
        val session=session(a,fast=true,watchFactory=reader.factory)
        val connection=session.getStore(if(a.imapStartTls)"imap" else "imaps") as IMAPStore
        var folder: IMAPFolder?=null
        val problem=AtomicReference<Exception?>(null)
        try {
            reader.call{GoogleMailAuth.withCredential(context,a,password){connection.connect(a.imapHost,a.imapPort,a.username,it)}}
            val f=connection.getFolder("INBOX") as IMAPFolder;folder=f
            val listener=object: ConnectionAdapter(){override fun closed(e: ConnectionEvent){problem.set(java.io.IOException("Соединение закрыто."))};override fun disconnected(e: ConnectionEvent){problem.set(java.io.IOException("Соединение прервано."))}}
            f.addConnectionListener(listener);connection.addConnectionListener(listener)
            reader.call{f.open(Folder.READ_ONLY)}
            if(!connection.hasCapability("IDLE")) {
                onConnected(false)
                while(currentCoroutineContext().isActive){delay(60_000);reader.call{f.messageCount};onChanged()}
            }else {
                f.addMessageCountListener(object: MessageCountAdapter(){override fun messagesAdded(e: MessageCountEvent){onChanged()};override fun messagesRemoved(e: MessageCountEvent){onChanged()}})
                f.addMessageChangedListener{onChanged()}
                // Opening/authentication keep the short timeout. Only a ready IDLE socket waits long.
                reader.timeout(20*60_000);onConnected(true)
                val start=android.os.SystemClock.elapsedRealtime()
                while(currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime()-start<20*60_000) {
                    problem.get()?.let{throw it}
                    try{reader.call{f.idle(true)}}catch(e: Exception){
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        if(android.os.SystemClock.elapsedRealtime()-start<20*60_000-5_000)throw e
                        break // Renew a quiet session after the bounded IDLE read expires.
                    }
                }
            }
        }finally {
            reader.close()
            withContext(NonCancellable+Dispatchers.IO){runCatching{folder?.forceClose()};runCatching{connection.close()}}
        }
    }
    fun verify(a: MailAccount,password: String) {
        try { store(a,password).use { } }
        catch(e: Exception) { throw MailConnectionException("Входящая почта IMAP",a.imapHost,a.imapPort,e) }
        try { session(a).getTransport("smtp").use { transport->GoogleMailAuth.withCredential(context,a,password){transport.connect(a.smtpHost,a.smtpPort,a.username,it)} } }
        catch(e: Exception) { throw MailConnectionException("Исходящая почта SMTP",a.smtpHost,a.smtpPort,e) }
    }
    fun inbox(a: MailAccount,password: String,cached: List<MailItem> = emptyList(),loadOlder: Boolean=false,
        pageSize: Int=InboxPaging.PAGE_SIZE,folderPath: String="INBOX",role: FolderRole=FolderRole.INBOX,checkCancelled: ()->Unit={}): InboxPage = store(a,password,fast=!loadOlder).use { store->
        val folder=store.getFolder(folderPath) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val count=folder.messageCount
            if(count==0)return@use InboxPage(emptyList(),InboxHistory(0,false,0),folder.uidValidity)
            // UID, unlike sequence number, survives arrivals and deletions between sessions.
            val sameFolder=cached.filter{it.validity==folder.uidValidity && it.uid>0 && it.remoteFolder.equals(folderPath,true)}
            val validCache=sameFolder.filter{role!=FolderRole.INBOX || it.localFolder=="inbox"}
            val oldestUid=validCache.minOfOrNull{it.uid}
            // History pages fetch only the next batch, not all previously downloaded bodies/headers.
            val retained=if(oldestUid==null)emptyList() else if(loadOlder) {
                folder.getMessageByUID(oldestUid)?.let{listOf(it)} ?: folder.getMessagesByUID(oldestUid,UIDFolder.LASTUID)
                    .filter{folder.getUID(it)>=oldestUid}.take(1)
            } else folder.getMessagesByUID(oldestUid,UIDFolder.LASTUID).filter{folder.getUID(it)>=oldestUid}
            val messages=if(retained.isEmpty()) {
                val range=InboxPaging.latest(count,pageSize)!!
                folder.getMessages(range.first,range.last).toList()
            } else if(loadOlder) {
                val range=InboxPaging.older(retained.minOf{it.messageNumber},pageSize)
                    ?:return@use InboxPage(emptyList(),InboxHistory(count,false),folder.uidValidity)
                folder.getMessages(range.first,range.last).toList()
            } else retained
            val cachedByUid=sameFolder.associateBy { it.uid }
            folder.fetch(messages.toTypedArray(),FetchProfile().apply {add(FetchProfile.Item.FLAGS);add(UIDFolder.FetchProfileItem.UID)})
            val unknown=messages.filter{cachedByUid[folder.getUID(it)]?.internetMessageId.isNullOrBlank()}
            if(unknown.isNotEmpty())folder.fetch(unknown.toTypedArray(),FetchProfile().apply{add(FetchProfile.Item.ENVELOPE)})
            val items=messages.map { m->
                checkCancelled()
                (m as? IMAPMessage)?.setPeek(true)
                val uid=folder.getUID(m)
                val existing=cachedByUid[uid]
                if(!loadOlder && existing!=null)return@map existing.copy(internetMessageId=existing.internetMessageId.ifBlank{(m as? MimeMessage)?.messageID.orEmpty()},localFolder=FolderRules.local(role),unread=!m.isSet(Flags.Flag.SEEN),starred=m.isSet(Flags.Flag.FLAGGED),answered=existing.answered || m.isSet(Flags.Flag.ANSWERED),forwarded=existing.forwarded || m.flags.contains("\$Forwarded"))
                val reused=existing?.takeIf{it.contentCached}
                // Pagination must advance even if a particular MIME body cannot be read.
                val content=if(reused!=null)DecodedMail(reused.body,reused.attachments) else DecodedMail("",emptyList())
                if(existing!=null)return@map existing.copy(localFolder=FolderRules.local(role),body=content.text,attachments=content.attachments,unread=!m.isSet(Flags.Flag.SEEN),starred=m.isSet(Flags.Flag.FLAGGED),answered=existing.answered || m.isSet(Flags.Flag.ANSWERED),forwarded=existing.forwarded || m.flags.contains("\$Forwarded"),previewOnly=reused?.previewOnly?:false,contentCached=reused!=null)
                val from=m.from?.firstOrNull() as? InternetAddress
                MailItem(FolderRules.id(a.id,folderPath,folder.uidValidity,uid),a.id,uid,folder.uidValidity,folderPath,from?.personal?:from?.address?:"Без отправителя",from?.address.orEmpty(),
                    HeaderText.addresses(m.getRecipients(Message.RecipientType.TO)),m.subject?:(if(role==FolderRole.DRAFTS)"" else "Без темы"),content.text,
                    (m.receivedDate?:m.sentDate)?.time?:0L,!m.isSet(Flags.Flag.SEEN),m.isSet(Flags.Flag.FLAGGED),attachments=content.attachments,previewOnly=false,contentCached=false,localFolder=FolderRules.local(role),internetMessageId=(m as? MimeMessage)?.messageID.orEmpty(),answered=m.isSet(Flags.Flag.ANSWERED),forwarded=m.flags.contains("\$Forwarded"),cc=HeaderText.addresses(m.getRecipients(Message.RecipientType.CC)))
            }
            val unread=if(loadOlder)null else folder.unreadMessageCount.takeIf{it>=0}
            InboxPage(items,InboxHistory(count,messages.minOf{it.messageNumber}>1,unread),folder.uidValidity)
        } finally { folder.close(false) }
    }
    fun unread(a: MailAccount,password: String,cached: List<MailItem>,checkCancelled: ()->Unit={}): UnreadPage=store(a,password,fast=true).use{store->
        val folder=store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val messages=folder.search(FlagTerm(Flags(Flags.Flag.SEEN),false))
            val byId=cached.associateBy{it.id}
            if(messages.isNotEmpty())folder.fetch(messages,FetchProfile().apply{add(FetchProfile.Item.FLAGS);add(UIDFolder.FetchProfileItem.UID)})
            val unknown=messages.filter{byId["${a.id}:${folder.uidValidity}:${folder.getUID(it)}"]?.internetMessageId.isNullOrBlank()}
            if(unknown.isNotEmpty())folder.fetch(unknown.toTypedArray(),FetchProfile().apply{add(FetchProfile.Item.ENVELOPE)})
            val items=messages.map{m->
                checkCancelled()
                val uid=folder.getUID(m)
                val id="${a.id}:${folder.uidValidity}:$uid"
                val existing=byId[id]
                if(existing!=null)return@map existing.copy(internetMessageId=existing.internetMessageId.ifBlank{(m as? MimeMessage)?.messageID.orEmpty()},unread=!m.isSet(Flags.Flag.SEEN),starred=m.isSet(Flags.Flag.FLAGGED),answered=existing.answered || m.isSet(Flags.Flag.ANSWERED),forwarded=existing.forwarded || m.flags.contains("\$Forwarded"))
                val from=m.from?.firstOrNull() as? InternetAddress
                MailItem(id,a.id,uid,folder.uidValidity,"INBOX",from?.personal?:from?.address?:"Без отправителя",from?.address.orEmpty(),
                    HeaderText.addresses(m.getRecipients(Message.RecipientType.TO)),m.subject?:"Без темы",existing?.body.orEmpty(),
                    (m.receivedDate?:m.sentDate)?.time?:0L,unread=!m.isSet(Flags.Flag.SEEN),starred=m.isSet(Flags.Flag.FLAGGED),localFolder=existing?.localFolder?:"unread-cache",
                    attachments=existing?.attachments.orEmpty(),previewOnly=existing?.previewOnly?:false,contentCached=existing?.contentCached?:false,internetMessageId=(m as? MimeMessage)?.messageID.orEmpty(),answered=m.isSet(Flags.Flag.ANSWERED),forwarded=m.flags.contains("\$Forwarded"),cc=HeaderText.addresses(m.getRecipients(Message.RecipientType.CC)))
            }
            UnreadPage(folder.uidValidity,items,folder.messageCount)
        }finally{folder.close(false)}
    }
    suspend fun contents(a: MailAccount,password: String,messages: List<MailItem>,onItem: suspend (ContentBatch)->Boolean,onStart: suspend (MailItem)->Boolean={true},
        around: suspend (MailItem,suspend ()->Boolean)->Boolean={_,action->action()})=withContext(Dispatchers.IO) {
        if(messages.isEmpty())return@withContext
        runInterruptible{store(a,password,fast=true)}.use{connection->
            val folder=connection.getFolder(messages.first().remoteFolder) as IMAPFolder
            runInterruptible{folder.open(Folder.READ_ONLY)}
            try {
                require(messages.all{it.validity==folder.uidValidity && it.remoteFolder==messages.first().remoteFolder}){"Папка изменилась. Обновите список писем."}
                HistoryBodies.transfer(messages,read={m->
                    val coroutine=currentCoroutineContext()
                    runInterruptible {
                        val budget=MailReadBudget(checkCancelled={coroutine.ensureActive()})
                        budget.check()
                        val remote=folder.getMessageByUID(m.uid)
                        if(remote==null)null else {
                            (remote as? IMAPMessage)?.setPeek(true)
                            val decoded=MimeReader(checkCancelled=budget::check).read(remote)
                            m.copy(headers=ReplyHeaders.read(remote),composer=decoded.composer,bcc=if(FolderRules.draft(m))HeaderText.addresses(remote.getRecipients(Message.RecipientType.BCC))else m.bcc,body=decoded.text,htmlBody=decoded.html,contentVersion=MailContent.FORMAT_VERSION,attachments=decoded.attachments,outgoing=emptyList(),previewOnly=false,contentCached=true)
                        }
                    }
                },save=onItem,describe=::friendlyError,before=onStart,around=around)
            }finally{withContext(NonCancellable){runInterruptible{runCatching{folder.close(false)}}}}
        }
    }
    fun content(a: MailAccount,password: String,message: MailItem,checkCancelled: ()->Unit={}): MailItem=store(a,password).use{store->
        val folder=store.getFolder(message.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            require(folder.uidValidity==message.validity){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessageByUID(message.uid)?:error("Письмо перемещено или удалено на сервере.")
            (remote as? IMAPMessage)?.setPeek(true)
            val budget=MailReadBudget(checkCancelled=checkCancelled)
            val decoded=MimeReader(checkCancelled=budget::check).read(remote)
            message.copy(headers=ReplyHeaders.read(remote),composer=decoded.composer,bcc=if(FolderRules.draft(message))HeaderText.addresses(remote.getRecipients(Message.RecipientType.BCC))else message.bcc,body=decoded.text,htmlBody=decoded.html,contentVersion=MailContent.FORMAT_VERSION,attachments=decoded.attachments,outgoing=emptyList(),previewOnly=false,contentCached=true,internetMessageId=message.internetMessageId.ifBlank{(remote as? MimeMessage)?.messageID.orEmpty()},recipients=message.recipients.ifBlank{HeaderText.decode((remote as? MimeMessage)?.getHeader("To",null).orEmpty())},cc=HeaderText.addresses(remote.getRecipients(Message.RecipientType.CC)))
        }finally{folder.close(false)}
    }
    suspend fun trashMany(a: MailAccount,password: String,messages: List<MailItem>,before: suspend ()->Boolean,onConfirmed: suspend (Set<Long>)->Boolean) {
        moveMany(a,password,messages,FolderRole.TRASH,before,onConfirmed)
    }
    suspend fun moveMany(a: MailAccount,password: String,messages: List<MailItem>,role: FolderRole,before: suspend ()->Boolean,onConfirmed: suspend (Set<Long>)->Boolean): String=withContext(Dispatchers.IO) {
        require(role in setOf(FolderRole.TRASH,FolderRole.JUNK,FolderRole.INBOX))
        val title=when(role){FolderRole.TRASH->"Корзина";FolderRole.JUNK->"Спам";else->"Входящие"}
        if(messages.isEmpty())return@withContext ""
        runInterruptible{store(a,password)}.use{connection->
            val imap=connection as IMAPStore
            val method=runInterruptible{TrashRules.method(imap.hasCapability("MOVE"),imap.hasCapability("UIDPLUS"))}
                ?:error("Сервер не поддерживает безопасное перемещение в «$title». Письма не перемещены.")
            val target=runInterruptible {
                val folders=connection.defaultFolder.list("*").mapNotNull{it as? IMAPFolder}
                val available=folders.map{TrashFolderInfo(it.fullName,it.attributes.toSet())}
                val name=(if(role==FolderRole.TRASH)TrashRules.destination(available)else SpamRules.destination(available,role))
                    ?:error("Не найдена папка «$title» на сервере. Письма не перемещены.")
                connection.getFolder(name)
            }
            val folder=connection.getFolder(messages.first().remoteFolder) as IMAPFolder
            require(!folder.fullName.equals(target.fullName,true)){"Письма уже находятся в папке «$title»."}
            runInterruptible{folder.open(Folder.READ_WRITE)}
            try {
                require(messages.all{it.accountId==a.id && it.remoteFolder==folder.fullName && it.validity==folder.uidValidity}){"Папка изменилась. Обновите список писем."}
                TrashTransfer.run(messages.map{it.uid},read={batch->runInterruptible{folder.getMessagesByUID(batch.toLongArray()).filterNotNull()}},
                    uid={folder.getUID(it)},move={remote->runInterruptible{
                        val chosen=remote.toTypedArray()
                        when(method) {
                            TrashMethod.MOVE->folder.moveMessages(chosen,target)
                            TrashMethod.COPY_UID_EXPUNGE->{folder.copyMessages(chosen,target);folder.setFlags(chosen,Flags(Flags.Flag.DELETED),true);folder.expunge(chosen)}
                        }
                    }},before=before,onConfirmed=onConfirmed)
            }finally{withContext(NonCancellable){runInterruptible{runCatching{folder.close(false)}}}}
            target.fullName
        }
    }
    fun markResponse(a: MailAccount,password: String,message: MailItem,forward: Boolean) = store(a,password).use { connection->
        val folder=connection.getFolder(message.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            require(message.validity==folder.uidValidity){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessageByUID(message.uid)?:return@use
            val permanent=folder.permanentFlags
            val flags=if(forward)Flags("\$Forwarded") else Flags(Flags.Flag.ANSWERED)
            val supported=if(forward)permanent.contains(Flags.Flag.USER) || permanent.contains("\$Forwarded") else permanent.contains(Flags.Flag.ANSWERED)
            if(supported)folder.setFlags(arrayOf(remote),flags,true)
        }finally{folder.close(false)}
    }
    fun flagsMany(a: MailAccount,password: String,messages: List<MailItem>,seen: Boolean?=null,starred: Boolean?=null): Set<Long> = store(a,password).use { store->
        if(messages.isEmpty())return@use emptySet()
        val folder=store.getFolder(messages.first().remoteFolder) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            require(messages.all{it.validity==folder.uidValidity}){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessagesByUID(messages.map{it.uid}.distinct().toLongArray()).filterNotNull()
            if(remote.isNotEmpty()){seen?.let{folder.setFlags(remote.toTypedArray(),Flags(Flags.Flag.SEEN),it)};starred?.let{folder.setFlags(remote.toTypedArray(),Flags(Flags.Flag.FLAGGED),it)}}
            remote.map{folder.getUID(it)}.toSet()
        } finally { folder.close(false) }
    }
    fun flags(a: MailAccount,password: String,m: MailItem,seen: Boolean?=null,starred: Boolean?=null) = store(a,password).use { store->
        val folder=store.getFolder(m.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            require(folder.uidValidity==m.validity){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessageByUID(m.uid)?:error("Письмо уже перемещено или удалено на сервере.")
            seen?.let { remote.setFlag(Flags.Flag.SEEN,it) }
            starred?.let { remote.setFlag(Flags.Flag.FLAGGED,it) }
        } finally { folder.close(false) }
    }
    fun attachment(a: MailAccount,password: String,m: MailItem,attachment: Attachment,out: OutputStream) = store(a,password).use { store->
        val folder=store.getFolder(m.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            require(folder.uidValidity==m.validity){"Обновите письмо перед сохранением вложения."}
            var part: Part=folder.getMessageByUID(m.uid)?:error("Письмо больше не найдено.")
            (part as? IMAPMessage)?.setPeek(true)
            if(attachment.path!="root")attachment.path.split('.').forEach { part=(part.content as Multipart).getBodyPart(it.toInt()) }
            part.inputStream.use { input->
                val bytes=ByteArray(64*1024);var total=0L
                while(true){val n=input.read(bytes);if(n<0)break;total+=n;require(total<=25L*1024*1024){"В этой версии вложение ограничено 25 МБ."};out.write(bytes,0,n)}
            }
        } finally { folder.close(false) }
    }
    fun original(a: MailAccount,password: String,m: MailItem,out: OutputStream) = store(a,password).use { connection->
        val folder=connection.getFolder(m.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            require(folder.uidValidity==m.validity){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessageByUID(m.uid) as? IMAPMessage
                ?:error("Письмо перемещено или удалено на сервере.")
            remote.setPeek(true)
            remote.mimeStream.use { input->input.copyTo(out,64*1024) }
        } finally { folder.close(false) }
    }
    fun folders(a: MailAccount,password: String): List<ServerFolder> = store(a,password,fast=true).use{connection->
        connection.defaultFolder.list("*").mapNotNull { folder->
            val f=folder as? IMAPFolder?:return@mapNotNull null
            val attrs=f.attributes.toList()
            if(attrs.any{it.equals("\\NoSelect",true)})return@mapNotNull null
            // LIST already gives names and roles. Avoid two STATUS round trips per folder;
            // counts are refreshed with that folder's normal UID synchronization.
            ServerFolder(a.id,f.fullName,f.name,FolderRules.role(f.fullName,attrs))
        }
    }
    data class ServerCopy(val folder: String,val uid: Long,val validity: Long)
    fun sentCopies(a: MailAccount,password: String,source: MailItem): List<MailItem> = store(a,password,fast=true).use{connection->
        val folders=connection.defaultFolder.list("*").mapNotNull{it as? IMAPFolder}.filter{FolderRules.role(it.fullName,it.attributes.toList())==FolderRole.SENT}
        folders.flatMap{folder->
            folder.open(Folder.READ_ONLY)
            try{folder.search(MessageIDTerm(source.internetMessageId)).map{m->source.copy(uid=folder.getUID(m),validity=folder.uidValidity,remoteFolder=folder.fullName,localFolder="remote-sent")}}
            finally{folder.close(false)}
        }
    }
    fun append(a: MailAccount,password: String,draft: ComposeDraft,role: FolderRole,revision: Long,origin: MailItem?): ServerCopy = store(a,password).use{connection->
        require(role==FolderRole.SENT || role==FolderRole.DRAFTS)
        val existing=connection.defaultFolder.list("*").mapNotNull{it as? IMAPFolder}.firstOrNull{FolderRules.role(it.fullName,it.attributes.toList())==role}
        val folder=existing?:((connection.getFolder(if(role==FolderRole.SENT)"Sent" else "Drafts") as IMAPFolder).also{
            require(it.exists() || it.create(Folder.HOLDS_MESSAGES)){"Не удалось создать серверную папку."}
            runCatching{it.isSubscribed=true}
        })
        folder.open(Folder.READ_WRITE)
        try {
            val messageId=MessageIdentity.forDraft(draft.id,draft.internetMessageId)
            val term=if(role==FolderRole.DRAFTS)AndTerm(MessageIDTerm(messageId),AndTerm(HeaderTerm("X-LiftZip-Draft-ID",draft.id),HeaderTerm("X-LiftZip-Draft-Revision",revision.toString())))else MessageIDTerm(messageId)
            var savedUid=folder.search(term).maxOfOrNull{folder.getUID(it)}
            if(savedUid==null) {
                val message=mimeMessage(a,draft,revision.takeIf{role==FolderRole.DRAFTS})
                message.setFlag(Flags.Flag.SEEN,true)
                if((connection as IMAPStore).hasCapability("UIDPLUS"))savedUid=folder.appendUIDMessages(arrayOf(message))?.firstOrNull()?.uid?.takeIf{it>0}
                else folder.appendMessages(arrayOf(message))
                if(savedUid==null)savedUid=folder.search(term).maxOfOrNull{folder.getUID(it)}
            }
            val uid=savedUid?:throw ServerCopyUnconfirmedException()
            if(role==FolderRole.DRAFTS && (connection as IMAPStore).hasCapability("UIDPLUS")) {
                val old=folder.search(HeaderTerm("X-LiftZip-Draft-ID",draft.id)).filter{folder.getUID(it)!=uid}.toMutableList()
                if(origin!=null && origin.accountId==a.id && origin.remoteFolder==folder.fullName && origin.validity==folder.uidValidity && origin.uid!=uid)folder.getMessageByUID(origin.uid)?.let{m->if(old.none{folder.getUID(it)==origin.uid})old+=m}
                if(old.isNotEmpty()){folder.setFlags(old.toTypedArray(),Flags(Flags.Flag.DELETED),true);folder.expunge(old.toTypedArray())}
            }
            ServerCopy(folder.fullName,uid,folder.uidValidity)
        }finally{folder.close(false)}
    }
    fun removeDraft(a: MailAccount,password: String,origin: MailItem)=store(a,password).use{connection->
        val f=connection.getFolder(origin.remoteFolder) as IMAPFolder;f.open(Folder.READ_WRITE)
        try {
            require(f.uidValidity==origin.validity){"Папка черновиков изменилась. Обновите список."}
            val m=f.getMessageByUID(origin.uid)?:return@use
            require((connection as IMAPStore).hasCapability("UIDPLUS")){"Этот сервер не поддерживает безопасную очистку старой копии черновика."}
            f.setFlags(arrayOf(m),Flags(Flags.Flag.DELETED),true);f.expunge(arrayOf(m))
        }finally{f.close(false)}
    }
    private fun mimeMessage(a: MailAccount,draft: ComposeDraft,revision: Long?=null): MimeMessage =
        outgoingMime().build(session(a),a,draft,if(revision!=null)MimePurpose.DRAFT else MimePurpose.SENT,revision).message
    private fun outgoingMime()=OutgoingMime { file->
        context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось открыть ${file.name}")
    }
    /** Read only envelope/thread headers. READ_ONLY + PEEK never changes Seen. */
    fun replyHeaders(a: MailAccount,password: String,source: MailItem): MessageHeaders=store(a,password).use{connection->
        val folder=connection.getFolder(source.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            require(folder.uidValidity==source.validity){"Папка исходного письма изменилась. Обновите письмо перед ответом."}
            val remote=folder.getMessageByUID(source.uid)?:error("Исходное письмо удалено. Не удалось получить заголовки ответа.")
            (remote as? IMAPMessage)?.setPeek(true)
            ReplyHeaders.read(remote)
        }finally{folder.close(false)}
    }
    fun send(a: MailAccount,password: String,draft: ComposeDraft) {
        val session=session(a)
        val built=outgoingMime().build(session,a,draft,MimePurpose.SEND)
        val message=built.message
        val transport=session.getTransport("smtp")
        try {
            GoogleMailAuth.withCredential(context,a,password){transport.connect(a.smtpHost,a.smtpPort,a.username,it)}
            try { transport.sendMessage(message,built.envelope) }
            catch(e: Exception){throw DeliveryUncertainException()}
        } finally {
            // A failed QUIT must not turn an accepted message into a retry.
            runCatching { transport.close() }
        }
    }
}
