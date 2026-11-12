package ru.liftzip.mail

import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.*
import jakarta.mail.internet.*
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

enum class MimePurpose { SEND, DRAFT, SENT }
data class BuiltMail(val message: MimeMessage,val envelope: Array<Address>)
fun interface OutgoingResources { fun open(file: OutgoingFile): InputStream }

/** The only body/MIME builder used by SMTP, server drafts and Sent APPEND. No Android/network dependency. */
class OutgoingMime(private val resources: OutgoingResources) {
    fun build(session: Session,account: MailAccount,draft: ComposeDraft,purpose: MimePurpose=MimePurpose.SEND,revision: Long?=null): BuiltMail {
        require(draft.resourcesReady()){"Вложения ещё не подготовлены."}
        val body=ComposeContent.render(draft)
        val files=draft.filesToSend()
        val inline=files.filter{it.contentId.isNotBlank()}.groupBy{HtmlMail.cid(it.contentId)}
        ComposeContent.requireResources(draft,body)
        val selected=files.filter{it.contentId.isBlank() || HtmlMail.cid(it.contentId) in body.inlineIds}
        var total=body.plain.toByteArray().size.toLong()+body.html.toByteArray().size
        selected.forEach{file->var length=0L;resources.open(file).use{input->val bytes=ByteArray(8192);while(true){val count=input.read(bytes);if(count<0)break;length+=count;total+=count
            require(length<=20L*1024*1024){"Вложение больше 20 МБ: ${file.name}"}
            require(total<=25L*1024*1024){"Суммарный размер письма больше 25 МБ."}
        }}}
        require(total<=25L*1024*1024){"Суммарный размер письма больше 25 МБ."}
        val message=object: MimeMessage(session) {
            override fun updateMessageID(){setHeader("Message-ID",MessageIdentity.forDraft(draft.id,draft.internetMessageId))}
        }
        require(MailRules.validEmail(account.email) && account.senderName.none{it=='\r'||it=='\n'}){"Некорректный отправитель."}
        message.setFrom(InternetAddress(account.email,account.senderName,"UTF-8"))
        fun addresses(value: String): Array<Address> = ReplyHeaders.recipientList(value,purpose==MimePurpose.DRAFT).map{it as Address}.toTypedArray()
        message.setRecipients(Message.RecipientType.TO,addresses(draft.to))
        message.setRecipients(Message.RecipientType.CC,addresses(draft.cc))
        message.setRecipients(Message.RecipientType.BCC,addresses(draft.bcc))
        val envelope=message.allRecipients?:emptyArray()
        require(!draft.subject.contains('\r') && !draft.subject.contains('\n')){"Тема письма должна быть одной строкой."}
        message.setSubject(draft.subject,"UTF-8")
        message.sentDate=Date(draft.timestamp.takeIf{it>0}?:System.currentTimeMillis())
        val thread=MessageThread.outgoing(draft)
        if(thread.inReplyTo.isNotBlank())message.setHeader("In-Reply-To",thread.inReplyTo)
        if(thread.references.isNotEmpty())message.setHeader("References",thread.references.joinToString(" "))
        val alternatives=MimeMultipart("alternative")
        alternatives.addBodyPart(MimeBodyPart().apply{setText(body.plain,"UTF-8")})
        val htmlPart=MimeBodyPart().apply{setContent(body.html,"text/html; charset=UTF-8")}
        if(body.inlineIds.isEmpty())alternatives.addBodyPart(htmlPart)
        else {
            val related=MimeMultipart("related").apply{addBodyPart(htmlPart)}
            body.inlineIds.forEach{(original,cid)->related.addBodyPart(filePart(inline.getValue(original).single(),cid))}
            alternatives.addBodyPart(MimeBodyPart().apply{setContent(related)})
        }
        val ordinary=selected.filter{it.contentId.isBlank()}
        if(ordinary.isEmpty() && purpose!=MimePurpose.DRAFT)message.setContent(alternatives)
        else {
            val mixed=MimeMultipart("mixed").apply{addBodyPart(MimeBodyPart().apply{setContent(alternatives)})}
            ordinary.forEach{mixed.addBodyPart(filePart(it))}
            if(purpose==MimePurpose.DRAFT) {
                val metadata=ComposeCodec.draftMetadata(draft,body.inlineIds)
                require(metadata.toByteArray().size<=ComposeCodec.MAX_METADATA_BYTES){"Черновик слишком большой для сохранения структуры редактора."}
                mixed.addBodyPart(MimeBodyPart().apply{setContent(metadata,"${ComposeCodec.MIME}; charset=UTF-8");disposition=Part.INLINE})
                message.setHeader("X-LiftZip-Draft-ID",draft.id)
                message.setHeader("X-LiftZip-Draft-Revision",(revision?:0).toString())
                message.setFlag(Flags.Flag.DRAFT,true)
            }
            message.setContent(mixed)
        }
        if(purpose==MimePurpose.SEND)message.removeHeader("Bcc")
        message.saveChanges()
        return BuiltMail(message,envelope)
    }
    private fun filePart(file: OutgoingFile,cid: String?=null)=MimeBodyPart().apply {
        require(file.name.none{it=='\r'||it=='\n'} && file.mime.none{it=='\r'||it=='\n'}){"Некорректные данные вложения."}
        dataHandler=DataHandler(object: DataSource {
            override fun getInputStream()=resources.open(file)
            override fun getOutputStream(): OutputStream=throw UnsupportedOperationException()
            override fun getContentType()=file.mime
            override fun getName()=file.name
        })
        fileName=AttachmentRules.safeName(file.name)
        disposition=if(cid==null)Part.ATTACHMENT else Part.INLINE
        if(cid!=null)setHeader("Content-ID","<$cid>")
    }
}
