package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class WrappedQuoteHistoryTest {
    private val recipient="reader@example.com"
    private val composeUrl="//e.mail.ru/compose/?mailto=mailto%3a$recipient"
    private val latest="вторник, 17 ноября 2026 г. в 19:35 +08:00 от Автор <author@example.com>:"
    private val escapedLatest=org.jsoup.nodes.TextNode(latest).outerHtml()
    private val second="воскресенье, 15 ноября 2026 г. 20:23 +09:00 от Получатель"
    private val third="пятница, 13 ноября 2026 г. в 10:30 +08:00 от Получатель"
    private val oldest="четверг, 12 ноября 2026 г. в 12:00 +08:00 от Автор"
    private val plain="""
        Получено

        $latest

        >
        > Передано
        >
        > $second $recipient
        > ( $composeUrl ) :
        >
        >> Сумма уточнена
        >>
        >> $third <$recipient
        >> ( $composeUrl )>:
        >>
        >>> Подтверждение
        >>>
        >>> $oldest <author@example.com
        >>> >:
        >>>
        >>>> Исходный заказ
        >>>>
    """.trimIndent()
    private val html="""
        <div id="compose">
            <div>Получено</div><div><br></div><div id="signature"><br><br><br><br></div><br><br>
            $escapedLatest<br>
            <div id="previous"><blockquote style="border-left:1px solid blue">
                <div>Передано</div>
                $second <a href="$composeUrl">$recipient</a>:<br>
                <div><blockquote style="border-left:1px solid blue">
                    <div>Сумма уточнена</div>
                    $third &lt;<a href="$composeUrl">$recipient</a>&gt;:<br>
                    <div><blockquote style="border-left:1px solid blue">
                        <div>Подтверждение</div>
                        $oldest &lt;<a>author@example.com</a>&gt;:<br>
                        <div><blockquote style="border-left:1px solid blue">
                            <div><b>Исходный заказ</b></div>
                        </blockquote></div>
                    </blockquote></div>
                </blockquote></div>
            </blockquote></div>
        </div>
    """.trimIndent()

    @Test fun wrappedFourLevelHistoryIsFoldedWithoutLosingMarkupOrLeavingABlankTail() {
        val prepared=ReaderAnalysisCache().prepare(ReaderAnalysisCache.Key("wrapped",plain,HtmlMail.cleanSource(html),1))
        val history=prepared.second
        assertTrue(history.reason.toString(),history.separateHistory)
        assertEquals(4,prepared.first.messages.size)
        assertEquals("Получено",Jsoup.parse(history.currentHtml).text())
        val document=Jsoup.parse(UnifiedReaderDocument.assemble(history.currentHtml,history.quotedHtml,false,prepared.first.messages.size))
        assertEquals("⋯ 4",document.getElementById("mail-history-toggle")!!.text())
        assertTrue(document.getElementById(UnifiedReaderDocument.CURRENT_ID)!!.select("br,#signature").isEmpty())
        val quote=document.getElementById(UnifiedReaderDocument.HISTORY_ID)!!
        assertEquals("display:none",quote.attr("style"))
        assertEquals(4,quote.select("blockquote[style]").size)
        assertEquals("Исходный заказ",quote.select("b").text())
        assertEquals("https:$composeUrl",quote.selectFirst("a[href]")?.attr("href"))
    }

    @Test fun anAnnotationForADifferentAddressDoesNotCorroborateHistory() {
        val altered=plain.replace(composeUrl,composeUrl.replace(recipient,"other@example.com"))
        assertFalse(HtmlMail.analyzeHistory(HtmlMail.cleanSource(html),ConversationText.parse(altered)).separateHistory)
    }

    @Test fun changedContentOrAdditionalReplyIsKeptVisible() {
        val conversation=ConversationText.parse(plain)
        for(candidate in listOf(
            html.replace("Сумма уточнена","Сумма изменилась"),
            html.replace("</blockquote></div>\n</div>","</blockquote></div><p>Новый ответ после цитаты</p>\n</div>"),
            html.replace(latest.substringBefore(" <"),"вторник, 17 ноября 2026 г. в 19:35 +08:00 от Другой")
        )) {
            assertNotEquals(html,candidate)
            val safe=HtmlMail.cleanSource(candidate)
            val history=HtmlMail.analyzeHistory(safe,conversation)
            assertFalse(history.separateHistory)
            assertEquals(safe,history.currentHtml)
        }
    }
}
