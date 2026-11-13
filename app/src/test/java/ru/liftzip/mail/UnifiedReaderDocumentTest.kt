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
}
