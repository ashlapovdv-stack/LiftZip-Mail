package ru.liftzip.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class DecodedMail(val text: String, val attachments: List<Attachment>,val html: String="")

class MimeReader(private val checkCancelled: ()->Unit={},private val htmlToText: (String)->String=HtmlMail::text) {
    private val maxBytes=512*1024
    fun read(part: Part, path: String="root", depth: Int=0): DecodedMail {
        checkCancelled()
        if(depth>12) return DecodedMail("[Часть письма не показана: слишком сложная структура]",emptyList())
        val filename=part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        val mime=runCatching { ContentType(part.contentType).baseType }.getOrDefault("application/octet-stream")
        val contentId=part.getHeader("Content-ID")?.firstOrNull()?.let(HtmlMail::cid).orEmpty()
        if(part.disposition.equals(Part.ATTACHMENT,true) || !filename.isNullOrBlank() || part.isMimeType("message/rfc822") || contentId.isNotBlank() && mime.startsWith("image/")) {
            val name=filename?:if(mime.startsWith("image/"))"Изображение-${path}."+when(mime){"image/jpeg"->"jpg";"image/png"->"png";"image/gif"->"gif";"image/webp"->"webp";else->"img"}else "Вложение.eml"
            return DecodedMail("",listOf(Attachment(path,name,mime,part.size,contentId)))
        }
        if(part.isMimeType("multipart/*")) {
            checkCancelled()
            val multi=part.content as Multipart
            val pieces=(0 until multi.count.coerceAtMost(100)).map { index->read(multi.getBodyPart(index),if(path=="root")"$index" else "$path.$index",depth+1) }
            if(part.isMimeType("multipart/alternative")) {
                val preferred=(0 until multi.count.coerceAtMost(100)).indexOfFirst { multi.getBodyPart(it).isMimeType("text/plain") }
                return DecodedMail((pieces.getOrNull(preferred)?.takeIf{it.text.isNotBlank()}?:pieces.firstOrNull { it.text.isNotBlank() })?.text.orEmpty(),pieces.flatMap { it.attachments },pieces.firstOrNull{it.html.isNotBlank()}?.html.orEmpty())
            }
            val html=if(pieces.any{it.html.isNotBlank()})pieces.filter{it.html.isNotBlank() || it.text.isNotBlank()}.joinToString("<hr>"){it.html.ifBlank{HtmlMail.plain(it.text)}}.take(maxBytes)else ""
            return DecodedMail(pieces.map { it.text }.filter(String::isNotBlank).joinToString("\n\n").take(maxBytes),pieces.flatMap { it.attachments },html)
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
            val html=if(part.isMimeType("text/html"))HtmlMail.cleanSource(text) else ""
            val clean=if(part.isMimeType("text/html"))htmlToText(html) else text
            return DecodedMail(clean.trim()+(if(truncated)"\n\n[Показана часть длинного письма]" else ""),emptyList(),html)
        }
        return DecodedMail("",emptyList())
    }
}
