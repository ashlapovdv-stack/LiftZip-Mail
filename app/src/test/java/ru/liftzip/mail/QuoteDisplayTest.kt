package ru.liftzip.mail

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class QuoteDisplayTest {
    @Test fun flattenedOlderMessageGetsItsOwnNestedLineWithoutLosingMarkup() {
        val html="<blockquote style='border-left:1px solid blue'><div>New quote<br>──────────<br>Sender пишет:<br><b>Old quote</b><img src='cid:photo'></div></blockquote>"
        val messages=listOf(QuotedMessage(null,null,"New quote"),QuotedMessage("Sender",null,"Old quote",attribution="Sender пишет:"))
        val result=QuoteDisplay.nested(html,messages)
        val doc=Jsoup.parseBodyFragment(result)
        assertEquals(2,doc.select("blockquote").size)
        assertEquals("Old quote",doc.selectFirst("blockquote blockquote b")!!.text())
        assertEquals("cid:photo",doc.selectFirst("blockquote blockquote img")!!.attr("src"))
        assertEquals(Jsoup.parseBodyFragment(html).text(),doc.text())
    }
    @Test fun existingNestedQuoteAndSingleMessageArePreserved() {
        val html="<blockquote>New quote<blockquote>Sender пишет:<br>Old quote</blockquote></blockquote>"
        val messages=listOf(QuotedMessage(null,null,"New quote"),QuotedMessage(null,null,"Old quote",attribution="Sender пишет:"))
        assertEquals(2,Jsoup.parseBodyFragment(QuoteDisplay.nested(html,messages)).select("blockquote").size)
        assertEquals(html,QuoteDisplay.nested(html,messages.take(1)))
    }
    @Test fun ambiguousHeadersAreNotRestructured() {
        val html="<blockquote><div>Sender пишет:<br>Old quote</div><div>Sender пишет:<br>Old quote</div></blockquote>"
        val messages=listOf(QuotedMessage(null,null,"New"),QuotedMessage(null,null,"Old quote",attribution="Sender пишет:"))
        assertEquals(1,Jsoup.parseBodyFragment(QuoteDisplay.nested(html,messages)).select("blockquote").size)
    }
}
