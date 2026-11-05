package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.Part
import jakarta.mail.internet.*
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MimeReaderTest {
    private val session=Session.getInstance(Properties())
    private val reader=MimeReader { it.replace(Regex("<[^>]+>"),"") }
    @Test fun cyrillicPlainTextSurvivesDecoding() {
        val message=MimeMessage(session).apply{setText("Письмо: энкодер TS5690N9038","UTF-8");saveChanges()}
        assertEquals("Письмо: энкодер TS5690N9038",reader.read(message).text)
    }
    @Test fun alternativePrefersPlainTextEvenWhenHtmlComesFirst() {
        val multi=MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply{setContent("<b>HTML версия</b>","text/html; charset=UTF-8")})
            addBodyPart(MimeBodyPart().apply{setText("Текстовая версия","UTF-8")})
        }
        val message=MimeMessage(session).apply{setContent(multi);saveChanges()}
        assertEquals("Текстовая версия",reader.read(message).text)
    }
    @Test fun textAttachmentNeverBecomesMessageBody() {
        val multi=MimeMultipart().apply {
            addBodyPart(MimeBodyPart().apply{setText("Основное письмо","UTF-8")})
            addBodyPart(MimeBodyPart().apply{setText("Секретное вложение","UTF-8");fileName="request.txt";disposition=Part.ATTACHMENT})
        }
        val message=MimeMessage(session).apply{setContent(multi);saveChanges()}
        val decoded=reader.read(message)
        assertEquals("Основное письмо",decoded.text)
        assertEquals("request.txt",decoded.attachments.single().name)
        assertEquals("1",decoded.attachments.single().path)
    }
    @Test fun oversizedBodyIsLimitedWithoutLoadingAttachments() {
        val message=MimeMessage(session).apply{setText("a".repeat(600000),"UTF-8");saveChanges()}
        val decoded=reader.read(message)
        assertTrue(decoded.text.length<525000)
        assertTrue(decoded.text.contains("Показана часть"))
    }
    @Test fun htmlIsTextAndNeverExecuted() {
        val message=MimeMessage(session).apply{setContent("<p>Здравствуйте</p>","text/html; charset=UTF-8");saveChanges()}
        assertEquals("Здравствуйте",reader.read(message).text)
    }
    @Test fun htmlAlternativeIsKeptAlongsideThePreferredPlainText() {
        val multi=MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply{setText("Plain version","UTF-8")})
            addBodyPart(MimeBodyPart().apply{setContent("<p>Rich version</p><a href='https://example.com'>Read</a>","text/html; charset=UTF-8")})
        }
        val decoded=MimeReader().read(MimeMessage(session).apply{setContent(multi);saveChanges()})
        assertEquals("Plain version",decoded.text);assertTrue(decoded.html.contains("Rich version"));assertTrue(decoded.html.contains("https://example.com"))
    }
    @Test fun aRelatedInlineImageRetainsItsCidWithoutReadingItsPayload() {
        val image=object: MimeBodyPart() {override fun getInputStream(): java.io.InputStream=throw AssertionError("Inline image payload must stay lazy")}.apply {
            setHeader("Content-Type","IMAGE/PNG");setHeader("Content-ID","<photo@example.com>")
        }
        val multi=MimeMultipart("related").apply {
            addBodyPart(MimeBodyPart().apply{setContent("<p>Photo</p><img src='cid:photo@example.com'>","text/html; charset=UTF-8");setHeader("Content-Type","text/html; charset=UTF-8")})
            addBodyPart(image)
        }
        val decoded=MimeReader().read(multi.getBodyPart(0))
        assertTrue(decoded.html.contains("cid:photo@example.com"))
        val root=object: MimeBodyPart(){override fun getContent(): Any=multi;override fun isMimeType(type: String)=type=="multipart/*" || type=="multipart/related";override fun getContentType()="multipart/related"}
        val complete=MimeReader().read(root)
        assertEquals("photo@example.com",complete.attachments.single().contentId);assertEquals("image/png",complete.attachments.single().mime)
        assertEquals("1",complete.attachments.single().path)
    }
    @Test fun newsletterCssDoesNotLeakIntoPreviewsOrReplyText() {
        val m=MimeMessage(session).apply{setContent("<style>p {margin:0} @media screen {.x {width:100%!important}}</style><p>Прогноз Q4</p><a href='https://example.com'>Читать</a>","text/html; charset=UTF-8");saveChanges()}
        val decoded=MimeReader().read(m)
        assertFalse(decoded.text.contains("margin"));assertFalse(decoded.text.contains("important"))
        assertTrue(decoded.text.contains("Прогноз Q4"));assertTrue(decoded.text.contains("https://example.com"))
    }

}
