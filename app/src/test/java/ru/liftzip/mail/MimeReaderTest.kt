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
}
