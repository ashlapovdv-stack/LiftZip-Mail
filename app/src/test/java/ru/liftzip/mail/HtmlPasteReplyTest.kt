package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class HtmlPasteReplyTest {
    private fun decoded()=javaClass.classLoader!!.getResourceAsStream("html-paste-reply.eml")!!.use{MimeReader().read(MimeMessage(Session.getInstance(Properties()),it))}
    @Test fun providerQuoteWithFlattenedPlainFormattingStillFoldsLosslessly() {
        val mail=decoded()
        val conversation=ConversationText.parse(mail.text)
        val analysis=HtmlMail.analyzeHistory(mail.html,conversation)
        assertTrue("Reason: ${analysis.reason}",analysis.separateHistory)
        assertTrue(analysis.currentHtml.contains("Пришло на нашу html"))
        assertFalse(analysis.currentHtml.contains("Тест HTML-письма"))
        assertTrue(mail.html.contains("<table"))
    }
    @Test fun desktopHtmlHasInsetsAndLongLinesCannotWidenTheDocument() {
        val mail=decoded()
        val document=HtmlMail.document(mail.html,mail.text,emptyList(),HtmlMail.noHistory(mail.html))
        assertTrue(document.html.contains("padding-left:16px"))
        assertTrue(document.html.contains("overflow-wrap:anywhere"))
        assertTrue(document.html.contains("white-space:normal!important"))
    }
    @Test fun providerEvidenceDoesNotHideDifferentTextOrFollowingReplies() {
        val mail=decoded();val conversation=ConversationText.parse(mail.text)
        assertFalse(HtmlMail.analyzeHistory(mail.html.replace("Контактор","Другой товар"),conversation).separateHistory)
        assertFalse(HtmlMail.analyzeHistory(mail.html+"<p>Ещё один текущий ответ</p>",conversation).separateHistory)
    }

}
