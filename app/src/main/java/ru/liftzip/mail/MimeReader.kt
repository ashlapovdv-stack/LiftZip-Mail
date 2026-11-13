package ru.liftzip.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class DecodedMail(val text: String, val attachments: List<Attachment>,val html: String="",val composer: ComposerSnapshot?=null)

class MimeReader(private val checkCancelled: ()->Unit={},private val htmlToText: (String)->String=HtmlMail::text) {
    private val maxBytes=512*1024
    private val truncatedText="[Показана часть длинного письма]"
    private fun limitText(value: String)=if(value.length>maxBytes)value.take(maxBytes)+"\n\n$truncatedText" else value
    private fun limitHtml(value: String)=if(value.length>maxBytes)value.take(maxBytes)+"<p>$truncatedText</p>" else value
    fun read(part: Part, path: String="root", depth: Int=0,draftId: String?=null): DecodedMail {
        checkCancelled()
        if(depth>12) return DecodedMail("[Часть письма не показана: слишком сложная структура]",emptyList())
        val expectedDraftId=if(depth==0)part.getHeader("X-LiftZip-Draft-ID")?.firstOrNull() else draftId
        val filename=part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        if(part.isMimeType(ComposeCodec.MIME) && expectedDraftId!=null && filename.isNullOrBlank() && part.disposition.equals(Part.INLINE,true)) {
            val bytes=ByteArrayOutputStream()
            part.inputStream.use{input->val buffer=ByteArray(8192);while(true){checkCancelled();val n=input.read(buffer);if(n<0)break
                if(bytes.size()+n>ComposeCodec.MAX_METADATA_BYTES)return DecodedMail("",emptyList())
                bytes.write(buffer,0,n)
            }}
            return DecodedMail("",emptyList(),composer=ComposeCodec.readMetadata(bytes.toString("UTF-8"),expectedDraftId))
        }
        val mime=runCatching { ContentType(part.contentType).baseType }.getOrDefault("application/octet-stream").lowercase()
        val contentId=part.getHeader("Content-ID")?.firstOrNull()?.let(HtmlMail::cid).orEmpty()
        if(part.disposition.equals(Part.ATTACHMENT,true) || !filename.isNullOrBlank() || part.isMimeType("message/rfc822") || contentId.isNotBlank() && mime.startsWith("image/")) {
            val name=filename?:if(mime.startsWith("image/"))"Изображение-${path}."+when(mime){"image/jpeg"->"jpg";"image/png"->"png";"image/gif"->"gif";"image/webp"->"webp";else->"img"}else "Вложение.eml"
            return DecodedMail("",listOf(Attachment(path,name,mime,part.size,contentId)))
        }
        if(part.isMimeType("multipart/*")) {
            checkCancelled()
            val multi=part.content as Multipart
            val pieces=(0 until multi.count.coerceAtMost(100)).map { index->read(multi.getBodyPart(index),if(path=="root")"$index" else "$path.$index",depth+1,expectedDraftId) }
            if(part.isMimeType("multipart/alternative")) {
                val preferred=(0 until multi.count.coerceAtMost(100)).indexOfFirst { multi.getBodyPart(it).isMimeType("text/plain") }
                return checked(DecodedMail((pieces.getOrNull(preferred)?.takeIf{it.text.isNotBlank()}?:pieces.firstOrNull { it.text.isNotBlank() })?.text.orEmpty(),pieces.flatMap { it.attachments },pieces.firstOrNull{it.html.isNotBlank()}?.html.orEmpty(),pieces.firstNotNullOfOrNull{it.composer}),depth,part)
            }
            val html=if(pieces.any{it.html.isNotBlank()})limitHtml(pieces.filter{it.html.isNotBlank() || it.text.isNotBlank()}.joinToString("<hr>"){it.html.ifBlank{HtmlMail.plain(it.text)}})else ""
            return checked(DecodedMail(limitText(pieces.map { it.text }.filter(String::isNotBlank).joinToString("\n\n")),pieces.flatMap { it.attachments },html,pieces.firstNotNullOfOrNull{it.composer}),depth,part)
        }
        if(part.isMimeType("text/plain")||part.isMimeType("text/html")) {
            checkCancelled()
            val charset=runCatching { Charset.forName(ContentType(part.contentType).getParameter("charset")?:"UTF-8") }.getOrDefault(Charsets.UTF_8)
            val buffer=ByteArrayOutputStream()
            var truncated=false
            part.inputStream.use { input->
                val bytes=ByteArray(8192)
                while(true) {
                    checkCancelled()
                    val count=input.read(bytes)
                    if(count<0)break
                    val room=maxBytes-buffer.size()
                    if(count>room){buffer.write(bytes,0,room);truncated=true;break}
                    buffer.write(bytes,0,count)
                }
            }
            val text=String(buffer.toByteArray(),charset)
            checkCancelled()
            val html=if(part.isMimeType("text/html"))HtmlMail.cleanSource(text)+(if(truncated)"<p>$truncatedText</p>" else "") else ""
            val clean=if(part.isMimeType("text/html"))htmlToText(html) else text
            return DecodedMail(clean+(if(truncated && html.isBlank())"\n\n$truncatedText" else ""),emptyList(),html)
        }
        return DecodedMail("",emptyList())
    }
    private fun checked(decoded: DecodedMail,depth: Int,part: Part): DecodedMail {
        if(depth!=0 || decoded.composer==null)return decoded
        val editor=decoded.composer
        val projection=ComposeContent.plain(ComposeDraft(accountId="",body=editor.text,quote=editor.quote,quoteIncluded=editor.quoteIncluded,bodyHtml=editor.bodyHtml))
        fun normalized(text: String)=text.replace("\r\n","\n").replace('\r','\n').trimEnd('\n')
        // A different client may edit the visible draft without updating our metadata.
        // Keep that complete visible body instead of resurrecting a stale editor snapshot.
        fun actual(name: String)=part.getHeader(name)?.joinToString(" ").orEmpty()
        fun addresses(raw: String)=ReplyHeaders.recipientList(raw,true).map{it.address.lowercase()}.sorted()
        val recipientsMatch=(editor.to==null || addresses(editor.to)==addresses(actual("To"))) &&
            addresses(editor.cc)==addresses(actual("Cc")) && addresses(editor.bcc)==addresses(actual("Bcc"))
        val subject=runCatching{MimeUtility.decodeText(actual("Subject"))}.getOrDefault(actual("Subject")).replace(Regex("\\r?\\n[ \\t]+")," ")
        val subjectMatches=editor.subject==null || editor.subject==subject
        val rendered=ComposeContent.render(ComposeDraft(id=part.getHeader("X-LiftZip-Draft-ID")?.firstOrNull().orEmpty(),accountId="",body=editor.text,quote=editor.quote,quoteIncluded=editor.quoteIncluded,bodyHtml=editor.bodyHtml)).html
        fun visibleHtml(html: String)=ComposeContent.safeQuoteHtml(html)
        val htmlMatches=visibleHtml(rendered)==visibleHtml(decoded.html)
        return if(normalized(projection)==normalized(decoded.text) && htmlMatches && recipientsMatch && subjectMatches)decoded else decoded.copy(composer=null)
    }

}
