package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class RichReplyDisplayTest {
    private fun mail()=javaClass.classLoader!!.getResourceAsStream("rich-reply-display.eml")!!.use{MimeReader().read(MimeMessage(Session.getInstance(Properties()),it))}
    @Test fun receivedReplyAlreadyContainsRichHtmlAndItsExpandedCitationRetainsIt() {
        val mail=mail()
        val source=Jsoup.parse(mail.html)
        assertEquals(2,source.select("table").size)
        assertEquals("жирный",source.select("b").first()!!.text())
        val history=HtmlMail.analyzeHistory(mail.html,ConversationText.parse(mail.text))
        assertTrue(history.reason.toString(),history.separateHistory)
        val quote=Jsoup.parse(history.quotedHtml)
        assertEquals(2,quote.select("table").size)
        assertEquals("жирный",quote.select("b").first()!!.text())
        assertEquals("курсивный",quote.select("i").text())
        assertTrue(quote.select("span[style]").any{it.attr("style").contains("198,40,40")})
        assertFalse(history.currentHtml.contains("<table"))
        assertFalse(quote.text().contains("Ответил на полученое письмо"))
    }
    @Test fun adaptiveAndOriginalViewsDoNotChangeStoredOrOutgoingSource() {
        val mail=mail();val source=mail.html
        val adaptive=HtmlMail.document(source,mail.text,emptyList())
        val original=HtmlMail.document(source,mail.text,emptyList(),originalScale=true)
        assertFalse(adaptive.originalScale);assertTrue(original.originalScale)
        assertTrue(adaptive.html.contains("white-space:normal!important"))
        assertFalse(original.html.contains("white-space:normal!important"))
        assertFalse(original.html.contains("mail-table-scroll"))
        assertTrue(original.html.contains("initial-scale=1"))
        assertTrue(original.html.contains("width:680px"));assertTrue(original.html.contains("white-space:nowrap"))
        assertEquals(source,mail.html)
    }
    @Test fun citeStylesAndInlineIdsStayAvailableToTheIndependentRenderer() {
        val html="<style>.bold{font-weight:bold}</style><p>Ответ</p><div class='gmail_attr'>On Thu, 12 Nov 2026, Person &lt;p@example.com&gt; wrote:</div><blockquote class='gmail_quote'><p class='bold'>Цитата</p><img src='cid:photo'></blockquote>"
        val body="Ответ\n\nOn Thu, 12 Nov 2026, Person <p@example.com> wrote:\n> Цитата"
        val history=HtmlMail.analyzeHistory(html,ConversationText.parse(body))
        assertTrue(history.separateHistory)
        assertTrue(history.quotedHtml.contains("font-weight:bold"));assertTrue(history.quotedHtml.contains("cid:photo"))
        val rendered=HtmlMail.document(history.quotedHtml,"Цитата",listOf(Attachment("1","pic.png","image/png",1,"photo")))
        assertTrue(rendered.images.values.single() is MailImageSource.Inline)
        assertTrue(rendered.html.contains("script-src 'none'"))
    }
}
