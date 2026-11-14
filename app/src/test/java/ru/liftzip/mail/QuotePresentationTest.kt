package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class QuotePresentationTest {
    private val header="понедельник, 09 ноября 2026г., 22:43 +09:00 от Отправитель sender@example.com:"
    private fun analyze(plain: String,html: String)=HtmlMail.analyzeHistory(
        HtmlMail.cleanSource("<p>Ответ</p><div>$header</div><blockquote>$html</blockquote>"),
        ConversationText.parse("Ответ\n\n$header\n"+plain.lineSequence().joinToString("\n"){"> $it"}))

    @Test fun richGenericCitationMatchesItsIndependentPlainPresentation() {
        val html="""<p style='font-weight:bold'>Важное уведомление</p>
            <p>Подробнее <a href='https://example.com/info'>на сайте</a>.</p>
            <ul><li><b>Первый пункт</b></li><li>Второй пункт</li></ul>
            <p>Телефон: (423) 265-73-62</p>
            <a href='https://example.com/logo'><img src='https://example.com/logo.png' alt='Логотип'></a>"""
        val plain="""*Важное уведомление*
            |Подробнее на сайте ( https://example.com/info ).
            |* *Первый пункт*
            |* Второй пункт
            |Телефон: (423) 265-73-62 ( tel:+74232657362 )
            |Логотип ( https://example.com/logo )""".trimMargin()
        val result=analyze(plain,html)
        assertTrue(result.reason.toString(),result.separateHistory)
        assertEquals("Ответ",Jsoup.parse(result.currentHtml).text())
        assertEquals(2,Jsoup.parse(result.quotedHtml).select("li").size)
        assertEquals(1,Jsoup.parse(result.quotedHtml).select("img[alt=Логотип]").size)
        assertEquals(1,Jsoup.parse(result.quotedHtml).select("a[href='https://example.com/info']").size)
    }
    @Test fun formattingCannotHideChangedWordsOrAnUnknownLink() {
        val html="<b>Важное</b><ul><li>Первый пункт</li></ul><a href='https://example.com/info'>Ссылка</a>"
        for(plain in listOf("*Иное*\n* Первый пункт\nСсылка (https://example.com/info)",
            "*Важное*\n* Первый пункт\nСсылка (https://other.example/info)",
            "*Важное*\n* Первый пункт\nСсылка (https://example.com/info)\nДополнение")) {
            assertFalse(analyze(plain,html).separateHistory)
        }
    }
    @Test fun literalStarsAndListMarkersAreContentWithoutSupportingMarkup() {
        assertFalse(analyze("*Важное*","Важное").separateHistory)
        assertFalse(analyze("* Первый пункт","Первый пункт").separateHistory)
        assertTrue(analyze("*Важное*","*Важное*").separateHistory)
    }
    @Test fun genericMismatchReportsTheFoundBoundaryRatherThanItsAbsence() {
        assertEquals(HtmlMail.HistoryReason.PLAIN_HISTORY_MISMATCH,analyze("Другое","Исходное").reason)
    }
    @Test fun presentationMatchingCannotFlattenNestedQuotationDepth() {
        val html="<b>Первое</b><blockquote><b>Второе</b></blockquote>"
        assertTrue(analyze("*Первое*\n> *Второе*",html).separateHistory)
        assertFalse(analyze("*Первое*\n*Второе*",html).separateHistory)
    }
    private val firstHeader="On Mon, 9 Nov 2026, Original <original@example.com> wrote:"
    private val secondHeader="On Tue, 10 Nov 2026, Reply <reply@example.com> wrote:"
    private val originalPlain="*Текст исходного письма*\nЦитата документа\nВложенная цитата документа"
    private val originalHtml="<p><b>Текст исходного письма</b></p><blockquote><p>Цитата документа</p>"+
        "<blockquote><p>Вложенная цитата документа</p></blockquote></blockquote>"
    private fun firstReplyHtml(old: String=originalHtml)="<p>Первый ответ</p><div class='gmail_attr'>${firstHeader.replace("<","&lt;").replace(">","&gt;")}</div><blockquote class='gmail_quote'>$old</blockquote>"
    private fun firstReplyPlain()="Первый ответ\n\n$firstHeader\n"+originalPlain.lineSequence().joinToString("\n"){"> $it"}
    private fun nestedReply(old: String=originalHtml): Pair<String,QuotedConversation> {
        val html="<p>Второй ответ</p><div class='gmail_attr'>${secondHeader.replace("<","&lt;").replace(">","&gt;")}</div><blockquote class='gmail_quote'>${firstReplyHtml(old)}</blockquote>"
        val plain="Второй ответ\n\n$secondHeader\n"+firstReplyPlain().lineSequence().joinToString("\n"){"> $it"}
        return HtmlMail.cleanSource(html) to ConversationText.parse(plain)
    }
    @Test fun replyingAgainPreservesAnAlreadyCorroboratedFlattenedProviderHistory() {
        val first=HtmlMail.analyzeHistory(HtmlMail.cleanSource(firstReplyHtml()),ConversationText.parse(firstReplyPlain()))
        assertTrue("The earlier message already has a valid provider/plain split",first.separateHistory)
        val (html,plain)=nestedReply()
        assertEquals(2,plain.messages.size)
        val result=HtmlMail.analyzeHistory(html,plain)
        assertTrue(result.reason.toString(),result.separateHistory)
        assertEquals("Второй ответ",Jsoup.parse(result.currentHtml).text())
        val old=Jsoup.parse(result.quotedHtml)
        assertEquals("Текст исходного письма",old.select("b").text())
        assertEquals(4,old.select("blockquote").size)
    }
    @Test fun nestedProviderCorroborationCannotHideChangedOrAdditionalHtmlWords() {
        for(old in listOf(originalHtml.replace("Цитата документа","Другой документ"),originalHtml+"<p>Дополнительное содержимое</p>")) {
            val (html,plain)=nestedReply(old)
            val result=HtmlMail.analyzeHistory(html,plain)
            assertFalse(result.separateHistory)
            assertEquals(html,result.currentHtml)
        }
    }
}
