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

}
