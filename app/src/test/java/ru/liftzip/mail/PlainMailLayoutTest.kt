package ru.liftzip.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class PlainMailLayoutTest {
    private val body="Вы получили заказ.\n\n№ заказа: 123\nДата заказа: 12.11.2026\nСостояние заказа: Pending\n\nТовары\n\n1x Деталь А - 100 ₽\n1x Деталь Б - 200 ₽\n\nИтого\n\nСумма: 300 ₽\nДоставка: 5 ₽\nИтого: 305 ₽\n\n\nКомментарий к Вашему заказу\n\nДоставить после 18:00.\nПозвонить перед приездом.\n"
    @Test fun rendererRetainsEveryPlainTextLineIncludingTheOrderComment() {
        val document=HtmlMail.document("",body,emptyList())
        val displayed=Jsoup.parse(document.html).selectFirst(".mail-plain")!!.wholeText()
        assertEquals(body,displayed)
        assertTrue(displayed.contains("Комментарий к Вашему заказу\n\nДоставить после 18:00.\nПозвонить перед приездом."))
    }
    @Test fun aMixedMimeContainingOnlyPlainTextKeepsItsCompleteBodyThroughRendering() {
        val wire="MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=plain-order\r\n\r\n--plain-order\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"+body.replace("\n","\r\n")+"\r\n--plain-order--\r\n"
        val decoded=MimeReader().read(MimeMessage(Session.getInstance(Properties()),wire.byteInputStream()))
        assertEquals("",decoded.html)
        val parsed=ConversationText.parse(decoded.text)
        assertTrue(parsed.messages.isEmpty())
        val document=HtmlMail.document(decoded.html,parsed.current,emptyList())
        assertEquals(decoded.text.replace("\r\n","\n"),Jsoup.parse(document.html).selectFirst(".mail-plain")!!.wholeText())
    }
    @Test fun spacesTabsBlankLinesAndLinksAreNotNormalizedByTheHtmlSerializer() {
        val text="  Первая строка\n\n\n\tВторая  строка\nhttps://example.com/path?q=1&x=2\n<literal> & текст\n"+"A".repeat(180)
        val document=HtmlMail.document("",text,emptyList())
        val doc=Jsoup.parse(document.html)
        assertEquals(text,doc.selectFirst(".mail-plain")!!.wholeText())
        assertEquals("https://example.com/path?q=1&x=2",doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.select("literal").isEmpty())
        assertTrue(document.html.contains("white-space:pre-wrap!important"))
        assertTrue(document.html.contains("overflow-wrap:anywhere"))
    }
    @Test fun originalHtmlAlsoKeepsSenderWhitespaceForItsOwnStyles() {
        val text="  Первая строка\n\n\tВторая  строка"
        val html="<div class='sender-text' style='white-space:pre-wrap'>$text</div>"
        val document=HtmlMail.document(html,"",emptyList(),originalScale=true)
        val displayed=Jsoup.parse(document.html).selectFirst(".sender-text")!!
        assertEquals(text,displayed.wholeText())
        assertTrue(displayed.attr("style").contains("white-space:pre-wrap"))
    }
}
