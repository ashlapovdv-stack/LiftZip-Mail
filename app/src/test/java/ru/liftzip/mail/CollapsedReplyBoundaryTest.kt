package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CollapsedReplyBoundaryTest {
    private val attribution="On Thu, 12 Nov 2026, Person <p@example.com> wrote:"
    private val plain="Reply\n\n$attribution\n> Earlier text"
    private fun source(current: String)=current+"<div class='gmail_attr'>On Thu, 12 Nov 2026, Person &lt;p@example.com&gt; wrote:</div><blockquote class='gmail_quote'><p>Earlier text</p></blockquote>"
    private fun render(current: String): String {
        val html=source(current);val parsed=ConversationText.parse(plain)
        val analysis=HtmlMail.analyzeHistory(html,parsed)
        assertTrue(analysis.reason.toString(),analysis.separateHistory)
        val originalQuote=analysis.quotedHtml
        val doc=HtmlMail.document(html,parsed.current,emptyList(),analysis)
        assertEquals(originalQuote,analysis.quotedHtml)
        assertTrue(Jsoup.parse(originalQuote).text().contains("Earlier text"))
        assertEquals(source(current),html)
        return doc.html
    }
    @Test fun onlyTrailingEmptyFormattingIsRemovedFromCollapsedReplies() {
        val doc=Jsoup.parse(render("<div id='current'>Reply<br><br><div><span>&nbsp;&#8203;</span><br></div></div><p><br></p>"))
        assertEquals("Reply",doc.body().text())
        assertTrue("Trailing blank lines must not push down the history control",doc.body().select("br").isEmpty())
    }
    @Test fun terminalParagraphAndWrapperSpacingCannotAccumulateAtTheCut() {
        val doc=Jsoup.parse(render("<div id='wrapper' style='padding:9px 16px 50px;margin:4px 0 28px'><p id='current' style='margin-top:0'>Reply</p><br><br></div>"))
        for(id in listOf("wrapper","current")) {
            val style=doc.getElementById(id)!!.attr("style")
            assertTrue(style,style.contains("margin-bottom:0!important"))
            assertTrue(style,style.contains("padding-bottom:0!important"))
        }
        assertTrue(doc.getElementById("wrapper")!!.attr("style").contains("padding:9px 16px 50px"))
    }
    @Test fun internalBlankLinesAndEarlierParagraphMarginsStayIntact() {
        val doc=Jsoup.parse(render("<div id='current'><p id='earlier' style='margin-bottom:19px'>First</p><div>Reply<br><br>Last line<br><br></div></div>"))
        assertEquals(2,doc.select("#current br").size)
        assertEquals("margin-bottom:19px",doc.getElementById("earlier")!!.attr("style"))
        assertEquals("First Reply Last line",doc.body().text())
    }
    @Test fun imagesRulesTablesAndDecoratedFootersRemainVisible() {
        for(tail in listOf("<img src='cid:photo' width='80' height='30'>","<hr>","<table width='300'><tr><td>Signature</td></tr></table>","<div id='panel' style='background:#225588;padding:20px'>Signature<br></div>")) {
            val doc=Jsoup.parse(render("<div>Reply</div>"+tail+"<div><br></div>"))
            when {
                tail.contains("img")->assertTrue(doc.text().contains("Изображение"))
                tail.contains("<hr")->assertEquals(1,doc.select("hr").size)
                tail.contains("table")->assertEquals("Signature",doc.select("td").text())
                else->{assertTrue(doc.getElementById("panel")!!.attr("style").contains("padding:20px"));assertEquals(1,doc.select("#panel br").size)}
            }
        }
    }
    @Test fun fullOriginalAndUnseparatedLettersKeepTheirTrailingFormatting() {
        val html=source("<p style='padding-bottom:36px'>Reply<br><br></p>")
        val parsed=ConversationText.parse(plain);val analysis=HtmlMail.analyzeHistory(html,parsed)
        val full=HtmlMail.document(html,plain,emptyList())
        val original=HtmlMail.document(html,plain,emptyList(),originalScale=true)
        assertTrue(full.html.contains("padding-bottom:36px"));assertTrue(Jsoup.parse(full.html).select("br").size>=2)
        assertTrue(original.html.contains("padding-bottom:36px"));assertTrue(Jsoup.parse(original.html).select("br").size>=2)
        val separatedOriginalScale=HtmlMail.document(html,parsed.current,emptyList(),analysis,originalScale=true)
        assertTrue(Jsoup.parse(separatedOriginalScale.html).select("br").isEmpty())
        assertEquals(html,source("<p style='padding-bottom:36px'>Reply<br><br></p>"))
    }
}
