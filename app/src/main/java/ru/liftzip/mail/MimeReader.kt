package ru.liftzip.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class DecodedMail(val text: String, val attachments: List<Attachment>)

class MimeReader(private val checkCancelled: ()->Unit={},private val htmlToText: (String)->String) {
    private val maxBytes=512*1024
    fun read(part: Part, path: String="root", depth: Int=0): DecodedMail {
        checkCancelled()
        if(depth>12) return DecodedMail("[Часть письма не показана: слишком сложная структура]",emptyList())
        val filename=part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        val mime=runCatching { ContentType(part.contentType).baseType }.getOrDefault("application/octet-stream")
        if(part.disposition.equals(Part.ATTACHMENT,true) || !filename.isNullOrBlank() || part.isMimeType("message/rfc822")) {
            return DecodedMail("",listOf(Attachment(path,filename?:"Вложение.eml",mime,part.size)))
        }
        if(part.isMimeType("multipart/*")) {
            checkCancelled()
            val multi=part.content as Multipart
            val pieces=(0 until multi.count.coerceAtMost(100)).map { index->read(multi.getBodyPart(index),if(path=="root")"$index" else "$path.$index",depth+1) }
            if(part.isMimeType("multipart/alternative")) {
                val preferred=(0 until multi.count.coerceAtMost(100)).indexOfFirst { multi.getBodyPart(it).isMimeType("text/plain") }
                return DecodedMail((pieces.getOrNull(preferred)?:pieces.firstOrNull { it.text.isNotBlank() })?.text.orEmpty(),pieces.flatMap { it.attachments })
            }
            return DecodedMail(pieces.map { it.text }.filter(String::isNotBlank).joinToString("\n\n").take(maxBytes),pieces.flatMap { it.attachments })
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
            val clean=if(part.isMimeType("text/html"))htmlToText(text) else text
            return DecodedMail(clean.trim()+(if(truncated)"\n\n[Показана часть длинного письма]" else ""),emptyList())
        }
        return DecodedMail("",emptyList())
    }
}
