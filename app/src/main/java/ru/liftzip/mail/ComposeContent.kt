package ru.liftzip.mail

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.jsoup.Jsoup

/** null on an old cache row means unknown; an empty value means fetched but absent. */
data class MessageHeaders(val messageId: String="",val replyTo: String="",val inReplyTo: String="",val references: List<String> = emptyList()): Serializable

data class QuotedSource(
    val accountId: String,val messageId: String,val uid: Long,val validity: Long,val folder: String,
    val sender: String,val recipients: String,val subject: String,val date: Long,
    val plain: String,val html: String,val headers: MessageHeaders?,val kind: String="reply",val senderEmail: String="",val serverKey: String=""
): Serializable {
    fun sourceMessage()=MailItem(messageId,accountId,uid,validity,folder,sender=sender,senderEmail=senderEmail,recipients=recipients,subject=subject,body=plain,date=date,htmlBody=html,headers=headers)
    fun attribution(): String {
        val whenSent=SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",Locale.US).format(Date(date))
        return if(kind=="forward")"---------- Forwarded Message ----------\nFrom: $sender\nDate: $whenSent\nSubject: $subject\nTo: $recipients"
            else "On $whenSent, $sender wrote:"
    }
}

/** Persisted editor state, distinct from the complete plain/HTML display projections. */
data class ComposerSnapshot(val text: String,val quote: QuotedSource?=null,val quoteIncluded: Boolean=true,val cc: String="",val bcc: String="",val resourceAliases: Map<String,String> = emptyMap(),val responseTo: String?=null,val responseKind: String="",val to: String?=null,val subject: String?=null): Serializable

data class ComposedBody(val plain: String,val html: String,val inlineIds: Map<String,String>)

object ComposeContent {
    fun quote(m: MailItem,kind: String)=QuotedSource(m.accountId,m.id,m.uid,m.validity,m.remoteFolder,
        listOf(m.sender.takeIf{it.isNotBlank()},m.senderEmail.takeIf{it.isNotBlank()}?.let{"<$it>"}).filterNotNull().joinToString(" "),
        m.recipients,m.subject,m.date,m.body.ifBlank{HtmlMail.text(m.htmlBody)},m.htmlBody,m.headers?:if(m.uid<=0)MessageHeaders(messageId=m.internetMessageId)else null,kind,m.senderEmail)
    fun plain(d: ComposeDraft): String {
        val quote=d.quote?.takeIf{d.quoteIncluded}
        return if(quote==null)d.body else listOf(d.body,quote.attribution(),quote.plain.lineSequence().joinToString("\n"){"> $it"}).filter{it.isNotBlank()}.joinToString("\n\n")
    }
    fun render(d: ComposeDraft): ComposedBody {
        val quote=d.quote?.takeIf{d.quoteIncluded}
        val plain=plain(d)
        val doc=Jsoup.parseBodyFragment("")
        doc.outputSettings().prettyPrint(false)
        doc.body().appendElement("div").attr("data-liftzip-author","1").html(escaped(d.body).replace("\n","<br>"))
        val inlineIds=linkedMapOf<String,String>()
        if(quote!=null) {
            val header=doc.body().appendElement("div").attr("class","gmail_attr")
            header.html(escaped(quote.attribution()).replace("\n","<br>"))
            val block=doc.body().appendElement("blockquote").attr("class","gmail_quote").attr("type","cite").attr("style","border-left:2px solid #a58ac7;margin:8px 0 8px 8px;padding-left:10px")
            block.html(if(quote.html.isNotBlank())safeQuoteHtml(quote.html)else escaped(quote.plain).replace("\n","<br>"))
            block.select("img[src]").forEach { image->
                val src=image.attr("src")
                if(src.startsWith("cid:",true)) {
                    val original=HtmlMail.cid(src.substring(4))
                    val mapped=inlineIds.getOrPut(original){MailImageLimits.hash(d.id+":"+original).take(40)+"@liftzip.mail"}
                    image.attr("src","cid:$mapped")
                }
            }
        }
        return ComposedBody(plain,doc.outerHtml(),inlineIds)
    }
    fun requireResources(d: ComposeDraft,body: ComposedBody=render(d)) {
        val files=d.filesToSend().filter{it.contentId.isNotBlank()}.groupBy{HtmlMail.cid(it.contentId)}
        body.inlineIds.keys.forEach{cid->require(files[cid]?.size==1){"Недоступно встроенное изображение цитаты: $cid"}}
    }
    fun escaped(text: String)=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("\r\n","\n").replace('\r','\n')
    /** Source sanitization only; never uses reader document/canvas/resource URLs. */
    fun safeQuoteHtml(source: String): String {
        val doc=Jsoup.parse(HtmlMail.cleanSource(source))
        doc.outputSettings().prettyPrint(false)
        doc.select("[src],[href],[background]").forEach{node->listOf("src","href","background").forEach{attribute->
            if(node.hasAttr(attribute)) {
                val value=node.attr(attribute)
                val allowed=if(attribute=="src")value.startsWith("cid:",true)||value.startsWith("data:image/",true)||HtmlMail.imageUrl(value)!=null else HtmlMail.link(value)!=null
                if(!allowed || value.contains("mail.liftzip.invalid",true))node.removeAttr(attribute)
            }
        }}
        fun css(value: String)=value.replace(Regex("(?is)url\\s*\\([^)]*(?:file:|content:|mail\\.liftzip\\.invalid)[^)]*\\)"),"")
        doc.select("[style]").forEach{it.attr("style",css(it.attr("style")))}
        doc.select("style").forEach{it.text(css(it.data()))}
        return doc.head().select("style").joinToString(""){it.outerHtml()}+doc.body().html()
    }
}

object MessageThread {
    private val token=Regex("<[^<>\\s@]+@[^<>\\s@]+>")
    fun id(raw: String)=raw.trim().takeIf{!it.contains('\r') && !it.contains('\n') && token.matches(it)}
    fun ids(raw: String): List<String> = token.findAll(raw.replace(Regex("\\r?\\n[ \\t]+")," ")).mapNotNull{ id(it.value) }.distinct().toList().takeLast(100)
    fun chain(headers: MessageHeaders): List<String> {
        val parent=id(headers.messageId)
        return (headers.references.mapNotNull(::id).filter{it!=parent}.distinct()+listOfNotNull(parent)).takeLast(100)
    }
}
