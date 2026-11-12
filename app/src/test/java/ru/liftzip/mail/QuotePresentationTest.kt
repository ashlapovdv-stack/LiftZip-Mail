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
}
