package ru.liftzip.mail

import android.content.Context
import android.net.Uri
import android.text.Html
import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.search.FlagTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Properties

class DeliveryUncertainException : Exception("Сервер не подтвердил результат отправки. Проверьте почту получателя или отправленные на сервере перед повторной отправкой.")
class MailConnectionException(val stage: String, val host: String, val port: Int, cause: Exception) : Exception(cause)

class MailGateway(private val context: Context) {
    private fun session(a: MailAccount): Session {
        val imap=if(a.imapStartTls)"imap" else "imaps"
        val p=Properties().apply {
            setProperty("mail.$imap.connectiontimeout","15000")
            setProperty("mail.$imap.timeout","20000")
            setProperty("mail.$imap.ssl.checkserveridentity","true")
            setProperty("mail.$imap.peek","true")
            setProperty("mail.$imap.partialfetch","true")
            if(a.imapStartTls){setProperty("mail.imap.starttls.enable","true");setProperty("mail.imap.starttls.required","true")}
            setProperty("mail.smtp.auth","true")
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
    private fun store(a: MailAccount,password: String): Store = session(a).getStore(if(a.imapStartTls)"imap" else "imaps").apply { connect(a.imapHost,a.imapPort,a.username,password) }
    fun verify(a: MailAccount,password: String) {
        try { store(a,password).use { } }
        catch(e: Exception) { throw MailConnectionException("Входящая почта IMAP",a.imapHost,a.imapPort,e) }
        try { session(a).getTransport("smtp").use { it.connect(a.smtpHost,a.smtpPort,a.username,password) } }
        catch(e: Exception) { throw MailConnectionException("Исходящая почта SMTP",a.smtpHost,a.smtpPort,e) }
    }
    fun inbox(a: MailAccount,password: String,cached: List<MailItem> = emptyList(),loadOlder: Boolean=false,
        pageSize: Int=InboxPaging.PAGE_SIZE,checkCancelled: ()->Unit={}): InboxPage = store(a,password).use { store->
        val folder=store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val count=folder.messageCount
            if(count==0)return@use InboxPage(emptyList(),InboxHistory(0,false))
            // UID, unlike sequence number, survives arrivals and deletions between sessions.
            val validCache=cached.filter{it.validity==folder.uidValidity && it.uid>0 && it.localFolder=="inbox"}
            val oldestUid=validCache.minOfOrNull{it.uid}
            // History pages fetch only the next batch, not all previously downloaded bodies/headers.
            val retained=if(oldestUid==null)emptyList() else if(loadOlder) {
                folder.getMessageByUID(oldestUid)?.let{listOf(it)} ?: folder.getMessagesByUID(oldestUid,UIDFolder.LASTUID)
                    .filter{folder.getUID(it)>=oldestUid}.take(1)
            } else folder.getMessagesByUID(oldestUid,UIDFolder.LASTUID).filter{folder.getUID(it)>=oldestUid}
            val extending=loadOlder && retained.isNotEmpty()
            val messages=if(retained.isEmpty()) {
                val range=InboxPaging.latest(count,pageSize)!!
                folder.getMessages(range.first,range.last).toList()
            } else if(loadOlder) {
                val range=InboxPaging.older(retained.minOf{it.messageNumber},pageSize)
                    ?:return@use InboxPage(validCache,InboxHistory(count,false))
                folder.getMessages(range.first,range.last).toList()
            } else retained
            folder.fetch(messages.toTypedArray(),FetchProfile().apply { add(FetchProfile.Item.ENVELOPE);add(FetchProfile.Item.FLAGS);add(UIDFolder.FetchProfileItem.UID) })
            val reader=MimeReader { Html.fromHtml(it,Html.FROM_HTML_MODE_LEGACY).toString() }
            val cachedById=cached.associateBy { it.id }
            val items=messages.map { m->
                checkCancelled()
                (m as? IMAPMessage)?.setPeek(true)
                val uid=folder.getUID(m)
                val from=m.from?.firstOrNull() as? InternetAddress
                val existing=cachedById["${a.id}:${folder.uidValidity}:$uid"]
                val reused=existing?.takeIf{it.contentCached}
                val content=if(reused!=null)DecodedMail(reused.body,reused.attachments) else reader.read(m)
                MailItem("${a.id}:${folder.uidValidity}:$uid",a.id,uid,folder.uidValidity,"INBOX",from?.personal?:from?.address?:"Без отправителя",from?.address.orEmpty(),
                    HeaderText.addresses(m.getRecipients(Message.RecipientType.TO)),m.subject?:"Без темы",content.text,
                    (m.receivedDate?:m.sentDate)?.time?:0L,!m.isSet(Flags.Flag.SEEN),m.isSet(Flags.Flag.FLAGGED),attachments=content.attachments,previewOnly=reused?.previewOnly?:false)
            }
            InboxPage(if(extending)(validCache+items).associateBy{it.id}.values.toList() else items,
                InboxHistory(count,messages.minOf{it.messageNumber}>1))
        } finally { folder.close(false) }
    }
    fun unread(a: MailAccount,password: String,cached: List<MailItem>,checkCancelled: ()->Unit={}): UnreadPage=store(a,password).use{store->
        val folder=store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val messages=folder.search(FlagTerm(Flags(Flags.Flag.SEEN),false))
            if(messages.isNotEmpty())folder.fetch(messages,FetchProfile().apply{add(FetchProfile.Item.ENVELOPE);add(FetchProfile.Item.FLAGS);add(UIDFolder.FetchProfileItem.UID)})
            val byId=cached.associateBy{it.id}
            val items=messages.map{m->
                checkCancelled()
                val uid=folder.getUID(m)
                val id="${a.id}:${folder.uidValidity}:$uid"
                val existing=byId[id]
                val from=m.from?.firstOrNull() as? InternetAddress
                MailItem(id,a.id,uid,folder.uidValidity,"INBOX",from?.personal?:from?.address?:"Без отправителя",from?.address.orEmpty(),
                    HeaderText.addresses(m.getRecipients(Message.RecipientType.TO)),m.subject?:"Без темы",existing?.body.orEmpty(),
                    (m.receivedDate?:m.sentDate)?.time?:0L,unread=!m.isSet(Flags.Flag.SEEN),starred=m.isSet(Flags.Flag.FLAGGED),localFolder=existing?.localFolder?:"unread-cache",
                    attachments=existing?.attachments.orEmpty(),previewOnly=existing?.previewOnly?:false,contentCached=existing?.contentCached?:false)
            }
            UnreadPage(folder.uidValidity,items,folder.messageCount)
        }finally{folder.close(false)}
    }
    fun content(a: MailAccount,password: String,message: MailItem): MailItem=store(a,password).use{store->
        val folder=store.getFolder(message.remoteFolder) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            require(folder.uidValidity==message.validity){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessageByUID(message.uid)?:error("Письмо перемещено или удалено на сервере.")
            (remote as? IMAPMessage)?.setPeek(true)
            val decoded=MimeReader{Html.fromHtml(it,Html.FROM_HTML_MODE_LEGACY).toString()}.read(remote)
            message.copy(body=decoded.text,attachments=decoded.attachments,previewOnly=false,contentCached=true)
        }finally{folder.close(false)}
    }
    fun flagsMany(a: MailAccount,password: String,messages: List<MailItem>,seen: Boolean): Set<Long> = store(a,password).use { store->
        if(messages.isEmpty())return@use emptySet()
        val folder=store.getFolder(messages.first().remoteFolder) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            require(messages.all{it.validity==folder.uidValidity}){"Папка изменилась. Обновите список писем."}
            val remote=folder.getMessagesByUID(messages.map{it.uid}.distinct().toLongArray()).filterNotNull()
            if(remote.isNotEmpty())folder.setFlags(remote.toTypedArray(),Flags(Flags.Flag.SEEN),seen)
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
                val bytes=ByteArray(8192);var total=0L
                while(true){val n=input.read(bytes);if(n<0)break;total+=n;require(total<=25L*1024*1024){"В этой версии вложение ограничено 25 МБ."};out.write(bytes,0,n)}
            }
        } finally { folder.close(false) }
    }
    fun send(a: MailAccount,password: String,draft: ComposeDraft) {
        // Providers may omit or misreport file length. Check actual bytes before SMTP.
        var total=0L
        draft.attachments.forEach { file->
            var length=0L
            (context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось открыть ${file.name}")).use { input->
                val bytes=ByteArray(8192)
                while(true){val n=input.read(bytes);if(n<0)break;length+=n;total+=n
                    require(length<=20L*1024*1024){"Вложение больше 20 МБ."}
                    require(total<=25L*1024*1024){"Суммарный размер вложений больше 25 МБ."}
                }
            }
        }
        val session=session(a)
        val message=object: MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID","<${draft.id}@local.liftzip.mail>") }
        }.apply {
            setFrom(InternetAddress(a.email,a.senderName,"UTF-8"))
            setRecipients(Message.RecipientType.TO,MailRules.recipients(draft.to).map { InternetAddress(it,true) }.toTypedArray())
            setSubject(draft.subject,"UTF-8");sentDate=Date()
            if(draft.attachments.isEmpty())setText(draft.body,"UTF-8")
            else {
                val multi=MimeMultipart().apply { addBodyPart(MimeBodyPart().apply { setText(draft.body,"UTF-8") }) }
                draft.attachments.forEach { file->
                    multi.addBodyPart(MimeBodyPart().apply {
                        dataHandler=DataHandler(object: DataSource {
                            override fun getInputStream(): InputStream {
                                val stream=context.contentResolver.openInputStream(Uri.parse(file.uri))?:error("Не удалось открыть ${file.name}")
                                return object: FilterInputStream(stream) {
                                    var total=0L
                                    private fun counted(n: Int): Int { if(n>0){total+=n;require(total<=20L*1024*1024){"Вложение больше 20 МБ"}};return n }
                                    override fun read(): Int = super.read().also { if(it>=0)counted(1) }
                                    override fun read(b: ByteArray,off: Int,len: Int)=counted(super.read(b,off,len))
                                }
                            }
                            override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
                            override fun getContentType()=file.mime
                            override fun getName()=file.name
                        })
                        fileName=file.name;disposition=Part.ATTACHMENT
                    })
                }
                setContent(multi)
            }
            saveChanges()
        }
        val transport=session.getTransport("smtp")
        try {
            transport.connect(a.smtpHost,a.smtpPort,a.username,password)
            try { transport.sendMessage(message,message.allRecipients) }
            catch(e: Exception){throw DeliveryUncertainException()}
        } finally {
            // A failed QUIT must not turn an accepted message into a retry.
            runCatching { transport.close() }
        }
    }
}
