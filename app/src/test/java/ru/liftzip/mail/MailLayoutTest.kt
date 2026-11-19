package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class MailLayoutTest {
    @Test fun existingAndUnknownStyleSettingsKeepTheStandardStyle() {
        assertEquals(MailStyle.STANDARD,MailStyle.fromKey(null));assertEquals(MailStyle.STANDARD,MailStyle.fromKey("missing"))
        MailStyle.entries.forEach{assertEquals(it,MailStyle.fromKey(it.key))}
    }
    @Test fun fixedTablesKeepTheirSourceWidthInsideAnIndependentScrollRegion() {
        val doc=HtmlMail.document("<table width='720'><tr><td>Backend checks</td><td>All jobs have passed</td></tr></table>","",emptyList())
        assertEquals(0,doc.layoutWidth);assertEquals("width=device-width",org.jsoup.Jsoup.parse(doc.html).selectFirst("meta[name=viewport]")!!.attr("content").substringBefore(","))
        assertFalse(doc.html.contains("initial-scale=1"));assertTrue(doc.html.contains("overflow-wrap:anywhere"))
        assertTrue(doc.html.contains(".mail-table-scroll{max-width:100%;overflow-x:auto}"));assertTrue(doc.html.contains("width=\"720\""))
    }
    @Test fun nestedEmailLayoutParticipatesInFitWhileItsDataTableScrollsSeparately() {
        val source="""
            <table id="template" width="100%"><tr><td>
                <table id="actions" width="100%"><tr>
                    <td><div style="min-width:520px">Reply to seller</div></td><td>Open conversation</td>
                </tr></table>
                <table id="items" width="1200"><tr><th>Item</th><th>Price</th></tr>
                    <tr><td>Sample</td><td>20</td></tr>
                </table>
            </td></tr></table>
        """.trimIndent()
        val page=HtmlMail.document(source,"",emptyList())
        val parsed=Jsoup.parse(page.html)
        assertEquals(listOf("items"),parsed.select(".mail-table-scroll > table").map{it.id()})
        assertEquals(520,page.layoutWidth)
        val original=HtmlMail.document(source,"",emptyList(),originalScale=true)
        assertTrue(Jsoup.parse(original.html).select(".mail-table-scroll").isEmpty())
    }
    @Test fun adaptiveTablesDoNotShrinkJustBecauseTheyHaveManyColumns() {
        // 0.5.19: the column count alone must not force a 640px desktop viewport.
        assertEquals(0,HtmlMail.layoutWidth("<table width='100%'><tr><th>Status</th><th>Job</th><th>Annotations</th></tr></table>"))
        // A row whose cells really are fixed keeps its summed width.
        assertEquals(840,HtmlMail.layoutWidth("<table><tr><td width='120'>Passed</td><td width='720'>Job</td></tr></table>"))
    }
    @Test fun responsiveTextAndLargeImagesDoNotForceDesktopScaling() {
        assertEquals(0,HtmlMail.layoutWidth("<p>Hello</p><img width='1200' src='cid:photo'>"))
        assertEquals(0,HtmlMail.layoutWidth("<table style='max-width:600px;width:100%'><tr><td>Text</td></tr></table>"))
        val plain=HtmlMail.document("","Plain mail",emptyList());assertEquals(0,plain.layoutWidth);assertTrue(plain.html.contains("width=device-width"))
    }
    @Test fun fixedCssWidthsAreRecognizedWhileMediaBreakpointsAreIgnored() {
        assertEquals(680,HtmlMail.layoutWidth("<style>.content{width:680px;}@media(max-width:900px){p{margin:0}}</style><div>Body</div>"))
        assertEquals(800,HtmlMail.layoutWidth("<div style='min-width:800px'>Body</div>"))
        assertEquals(0,HtmlMail.layoutWidth("<style>@media(max-width:900px){p{margin:0}}</style><p>Body</p>"))
        // A fixed width that only applies inside a conditional block must not set the viewport.
        assertEquals(0,HtmlMail.layoutWidth("<style>@media(max-width:900px){.wide{width:800px}}</style><p>Body</p>"))
    }
}
