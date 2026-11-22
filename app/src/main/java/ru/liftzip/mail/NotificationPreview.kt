package ru.liftzip.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import java.nio.charset.Charset

/** A bounded first excerpt. The complete body still uses the regular content cache. */
internal object NotificationPreview {
    const val READ_BYTES = 4096
    private const val MAX_MESSAGES = 3
    private const val MAX_PARTS = 12

    fun candidates(page: InboxPage,cursor: ArrivalCursor): List<MailItem> {
        val arrived=NotificationRules.arrivals(cursor,page.validity?:0,page.messages,complete=true).arrived
        return page.messages.asSequence()
            .filter{it.id in arrived && !it.contentCached && it.notificationPreview.isBlank()}
            .sortedByDescending{it.uid}.take(MAX_MESSAGES).toList()
    }

    fun read(message: Part,checkCancelled: ()->Unit={}): String {
        var bytesLeft=READ_BYTES
        var partsLeft=MAX_PARTS

        fun find(part: Part): String {
            checkCancelled()
            if(bytesLeft<=0 || partsLeft--<=0 || part.disposition.equals(Part.ATTACHMENT,true) || !part.fileName.isNullOrBlank())return ""
            if(part.isMimeType("multipart/*")) {
                val children=part.content as? Multipart?:return ""
                val count=children.count.coerceAtMost(partsLeft)
                val indexes=if(part.isMimeType("multipart/related"))(0 until minOf(count,1)).toList()else (0 until count).toList()
                val ordered=if(part.isMimeType("multipart/alternative"))indexes.sortedBy{!children.getBodyPart(it).isMimeType("text/plain")}else indexes
                for(i in ordered) {
                    val result=find(children.getBodyPart(i))
                    if(result.isNotBlank())return result
                    if(partsLeft<=0 || bytesLeft<=0)break
                }
                return ""
            }
            val html=part.isMimeType("text/html")
            if(!html && !part.isMimeType("text/plain"))return ""
            val charset=runCatching{Charset.forName(ContentType(part.contentType).getParameter("charset")?:"UTF-8")}.getOrDefault(Charsets.UTF_8)
            val buffer=ByteArray(bytesLeft)
            var length=0
            part.inputStream.use{input->
                while(length<buffer.size) {
                    checkCancelled()
                    val n=input.read(buffer,length,buffer.size-length)
                    if(n<=0)break
                    length+=n
                }
            }
            bytesLeft-=length
            val text=String(buffer,0,length,charset).trimEnd('\uFFFD')
            val plain=if(html)HtmlMail.text(text)else text
            return MailContent.snippet(ReplyText.split(plain).current).take(MailContent.PREVIEW_LENGTH)
        }

        return find(message)
    }
}
