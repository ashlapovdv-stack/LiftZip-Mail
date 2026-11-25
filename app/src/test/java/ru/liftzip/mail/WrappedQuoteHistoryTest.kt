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

    @Test fun aComposeLinkWrappedAfterItsOpeningParenthesisStillCorroboratesHistory() {
        val wrapped=plain.replace("$second $recipient\n> ( $composeUrl", "$second $recipient (\n> $composeUrl")
        assertNotEquals(plain,wrapped)
        val safe=HtmlMail.cleanSource(html)
        val history=HtmlMail.analyzeHistory(safe,ConversationText.parse(wrapped))
        assertTrue(history.reason.toString(),history.separateHistory)
        assertEquals("Получено",Jsoup.parse(history.currentHtml).text())

        val mismatched=wrapped.replace(composeUrl,composeUrl.replace(recipient,"other@example.com"))
        assertFalse(HtmlMail.analyzeHistory(safe,ConversationText.parse(mismatched)).separateHistory)
    }

    @Test fun repeatedSenderLinksCorroborateAcrossDifferentLineWraps() {
        val prefix=third.substringBeforeLast("Получатель")
        val old="$third <$recipient\n>> ( $composeUrl )>:"
        val safe=HtmlMail.cleanSource(html.replace(
            "$third &lt;<a href=\"$composeUrl\">$recipient</a>&gt;:",
            "$prefix<a href=\"$composeUrl\">$recipient</a> &lt;<a href=\"$composeUrl\">$recipient</a>&gt;:"
        ))
        val tokens=listOf("$prefix$recipient","(",composeUrl,")","<$recipient","(",composeUrl,")>:")
        for(mask in 0 until (1 shl (tokens.size-1))) {
            if(Integer.bitCount(mask)>3)continue
            val header=buildString {
                tokens.forEachIndexed{index,token->
                    if(index>0)append(if(mask and (1 shl (index-1))!=0)"\n>> " else " ")
                    append(token)
                }
            }
            val wrapped=plain.replace(old,header)
            assertNotEquals(plain,wrapped)
            val parsed=ConversationText.parse(wrapped)
            val history=HtmlMail.analyzeHistory(safe,parsed)
            assertTrue("Line breaks $mask: ${history.reason}",history.separateHistory)
            assertEquals("Получено",Jsoup.parse(history.currentHtml).text())
            assertEquals(4,parsed.messages.size)
        }
        val wrapped=plain.replace(old,tokens.joinToString(" "))
        val last=wrapped.lastIndexOf(composeUrl)
        val wrong=wrapped.replaceRange(last,last+composeUrl.length,composeUrl.replace(recipient,"other@example.com"))
        assertFalse(HtmlMail.analyzeHistory(safe,ConversationText.parse(wrong)).separateHistory)
        val wrongDepth=plain.replace(old,"$prefix$recipient (\n>>> $composeUrl ) <$recipient ( $composeUrl )>:")
        assertFalse(HtmlMail.analyzeHistory(safe,ConversationText.parse(wrongDepth)).separateHistory)
    }

    @Test fun adjacentUrlAndColonCorroborateEvenWithDifferentSpacing() {
        val url="https://example.com/item"
        for(colon in listOf(":", "：")) {
            val safe=HtmlMail.cleanSource(html.replace("Исходный заказ",
                "Исходный заказ <span>Ссылка$colon</span><a href=\"$url\">$url</a>"))
            for(space in listOf("", " ", "  ")) {
                val text=plain.replace("Исходный заказ","Исходный заказ Ссылка$colon$space$url")
                assertTrue(HtmlMail.analyzeHistory(safe,ConversationText.parse(text)).separateHistory)
                assertFalse(HtmlMail.analyzeHistory(safe,ConversationText.parse(text.replace(url,"https://example.com/other"))).separateHistory)
            }
        }
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
