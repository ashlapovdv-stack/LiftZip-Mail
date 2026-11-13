package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Sanitized real MIME structure; transport headers and identities are synthetic. */
class LinkedMimeHistoryTest {
    private fun decoded()=javaClass.getResourceAsStream("/mailru-linked-history.eml")!!.use{
        MimeReader().read(MimeMessage(Session.getInstance(Properties()),it))
    }
    @Test fun realAlternativeStructureCollapsesTheWholeHeaderAndPreservesRichExpansion() {
        val mime=decoded()
        val conversation=ConversationText.parse(mime.text)
        assertEquals("Текущий ответ",conversation.current.trim())
        assertTrue(conversation.messages.isNotEmpty())
        val analysis=HtmlMail.analyzeHistory(mime.html,conversation)
        assertTrue(analysis.reason.toString(),analysis.separateHistory)
        val current=Jsoup.parse(analysis.currentHtml).text()
        assertEquals("Текущий ответ",current.trim())
        val expanded=HtmlMail.document(mime.html,conversation.current,emptyList(),HtmlMail.noHistory(mime.html)).html
        val full=Jsoup.parse(expanded)
        assertTrue(full.text().contains("Первое старое письмо"))
        assertTrue(full.text().contains("Самое старое письмо"))
        assertEquals("mailto:client@example.com",full.selectFirst("a")!!.attr("href"))
        assertTrue(full.selectFirst("blockquote")!!.attr("style").contains("border-left"))
        assertEquals(analysis,HtmlMail.analyzeHistory(mime.html,ConversationText.parse(mime.text)))
    }
    @Test fun mismatchingSenderOrTrailingReplyKeepsTheEntireSource() {
        val mime=decoded();val conversation=ConversationText.parse(mime.text)
        for(html in listOf(mime.html.replace("client@example.com","other@example.com"),mime.html.replace("</blockquote>","</blockquote><p>Ответ после цитаты</p>"))) {
            val analysis=HtmlMail.analyzeHistory(html,conversation)
            assertFalse(analysis.separateHistory);assertEquals(html,analysis.currentHtml)
        }
    }
    @Test fun htmlDerivedPlainStillRecognizesLinkTargets() {
        val html="<p>Новый ответ</p><div>On 7 Nov 2026, client@example.com wrote:</div>"+
            "<blockquote>Старое письмо <a href='https://example.com/archive'>архив</a></blockquote>"
        val conversation=ConversationText.parse(HtmlMail.text(html))
        assertTrue(HtmlMail.analyzeHistory(html,conversation).separateHistory)
    }
}
