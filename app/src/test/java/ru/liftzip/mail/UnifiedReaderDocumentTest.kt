package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class UnifiedReaderDocumentTest {
    @Test fun joinsCurrentAndQuoteInOneBody() {
        val html = UnifiedReaderDocument.assemble(
            "<p>New answer</p>",
            "<blockquote><table><tr><td>Old answer</td></tr></table></blockquote>",
            true
        )
        val body = Jsoup.parseBodyFragment(html).body()
        assertEquals(1, body.select("#mail-current-message").size)
        assertEquals(1, body.select("#mail-quoted-history").size)
        assertEquals("New answer", body.select("#mail-current-message").text())
        assertEquals("Old answer", body.select("#mail-quoted-history td").text())
    }

    @Test fun collapsedHistoryRetainsMarkup() {
        val html = UnifiedReaderDocument.assemble("Current", "<b>Previous</b>", false)
        val history = Jsoup.parseBodyFragment(html).selectFirst("#mail-quoted-history")!!
        assertEquals("display:none", history.attr("style"))
        assertEquals("Previous", history.selectFirst("b")!!.text())
    }

    @Test fun sourceScriptsNeverReachCombinedDocument() {
        val html = UnifiedReaderDocument.assemble(
            "<p>Safe</p><script>alert(1)</script>",
            "<iframe src='https://example.invalid'></iframe><p>Quote</p>",
            true
        )
        val doc = Jsoup.parseBodyFragment(html)
        assertTrue(doc.select("script,iframe").isEmpty())
        assertEquals("Safe", doc.select("#mail-current-message").text())
        assertEquals("Quote", doc.select("#mail-quoted-history").text())
    }
    @Test fun wideTableRemainsInsideQuotedSection() {
        val html = UnifiedReaderDocument.assemble(
            "<p>Reply</p>",
            "<blockquote><table width='900'><tr><td>Quoted table</td></tr></table></blockquote>",
            true
        )
        val doc = Jsoup.parseBodyFragment(html)
        assertEquals("900", doc.selectFirst("#mail-quoted-history table")!!.attr("width"))
        assertEquals(1, doc.select("#mail-quoted-history blockquote").size)
        assertTrue(doc.select("#mail-current-message table").isEmpty())
    }

    @Test fun emptyQuoteDoesNotCreateSecondSection() {
        val html = UnifiedReaderDocument.assemble("<p>Only reply</p>", "", true)
        val doc = Jsoup.parseBodyFragment(html)
        assertTrue(doc.select("#mail-quoted-history").isEmpty())
        assertEquals("Only reply", doc.select("#mail-current-message").text())
    }

    @Test fun collapsedQuoteRetainsItsContentForReopening() {
        val collapsed = UnifiedReaderDocument.assemble("Reply", "<p>Old reply</p>", false)
        val expanded = UnifiedReaderDocument.assemble("Reply", "<p>Old reply</p>", true)
        val hidden = Jsoup.parseBodyFragment(collapsed).selectFirst("#mail-quoted-history")!!
        val shown = Jsoup.parseBodyFragment(expanded).selectFirst("#mail-quoted-history")!!
        assertEquals("display:none", hidden.attr("style"))
        assertFalse(shown.hasAttr("style"))
        assertEquals(hidden.text(), shown.text())
    }

    @Test fun unifiedDocumentUsesProductionImagePolicy() {
        val combined = UnifiedReaderDocument.assemble(
            "<p>Current</p>",
            "<p>Quote</p><img src='https://images.example/quote.png' alt='quote image'>",
            true
        )
        val blocked = HtmlMail.document(combined, "Current", emptyList(), HtmlMail.noHistory(combined))
        val allowed = HtmlMail.document(combined, "Current", emptyList(), HtmlMail.noHistory(combined), external = true)
        assertTrue(blocked.images.isEmpty())
        assertEquals(1, allowed.images.size)
        assertTrue(allowed.html.contains("mail-quoted-history"))
    }

    @Test fun collapsedAndExpandedHistoryAreDistinctCachedDocuments() {
        val collapsed = UnifiedReaderDocument.assemble("Current", "<p>Quote</p>", false)
        val expanded = UnifiedReaderDocument.assemble("Current", "<p>Quote</p>", true)
        val cache = ReaderDocumentCache()
        fun prepare(html: String) = cache.prepare(
            ReaderDocumentCache.Key("test:1", html, "Current", emptyList(),
                HtmlMail.noHistory(html), false, "#20241f", "#ffffff")
        )
        assertNotSame(prepare(collapsed), prepare(expanded))
        assertSame(prepare(collapsed), prepare(collapsed))
    }

    @Test fun nativeControlPrecedesHistoryInBothStatesAndSurvivesSanitization() {
        for(expanded in listOf(false,true)) {
            val html=UnifiedReaderDocument.assemble(HtmlMail.plain("New\nanswer"),HtmlMail.plain("Old\nanswer"),expanded,2)
            val rendered=HtmlMail.document(html,"",emptyList(),HtmlMail.noHistory(html))
            val body=Jsoup.parse(rendered.html).body()
            val control=body.selectFirst("a[aria-expanded]")!!
            assertEquals(UnifiedReaderDocument.TOGGLE_URL,control.attr("href"))
            assertEquals("Предыдущие сообщения: 2",control.attr("aria-label"))
            assertEquals(expanded.toString(),control.attr("aria-expanded"))
            assertEquals(UnifiedReaderDocument.HISTORY_ID,control.nextElementSibling()!!.id())
            assertEquals(2,body.select(".mail-plain").size)
            assertTrue(body.select("script,iframe").isEmpty())
        }
    }

    @Test fun currentTailIsNormalizedBeforeNativeHistoryControl() {
        for(current in listOf(HtmlMail.plain("Answer\n\n\n"),"<div style='padding-bottom:40px;margin-bottom:24px'><p>Answer</p><div><br></div></div>")) {
            val html=UnifiedReaderDocument.assemble(current,"<p>Old</p>",false)
            val body=Jsoup.parseBodyFragment(html).body()
            val reply=body.selectFirst("#mail-current-message")!!
            assertEquals("Answer",reply.text())
            assertTrue(reply.select("br").isEmpty())
            assertTrue(reply.attr("style").contains("margin-bottom:0!important"))
            val rendered=HtmlMail.document(html,"",emptyList(),HtmlMail.noHistory(html))
            assertTrue(rendered.html.contains("#mail-history-toggle{display:inline-flex"))
            assertTrue(rendered.html.contains("background:#e6e6e6!important"))
            assertEquals("⋯ 1",body.selectFirst("#mail-history-toggle")!!.text())
            assertFalse(body.selectFirst("#mail-history-toggle")!!.hasAttr("style"))
        }
    }

}
