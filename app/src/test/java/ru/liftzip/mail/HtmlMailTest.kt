package ru.liftzip.mail

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlMailTest {
    private val newsletter="""<html><head><style>p{margin:0}table{border-collapse:collapse} @media screen and (max-width:600px){.section{width:100%!important}}</style></head><body><p>Аналитики поделились прогнозом на Q4</p><a href="https://example.com/report?q=4&amp;x=1">Читать прогноз</a><img src="https://images.example.com/hero.png" alt="График"></body></html>"""
    @Test fun newsletterStylesNeverBecomeMessageTextOrObjectGlyphs() {
        val text=HtmlMail.text(newsletter)
        assertTrue(text.contains("Аналитики поделились прогнозом на Q4"))
        assertTrue(text.contains("https://example.com/report?q=4&x=1"))
        listOf("margin","@media","important","\uFFFC").forEach{assertFalse(text.contains(it))}
    }
    @Test fun formattingStylesAndLinkDestinationsSurviveSanitizing() {
        val clean=HtmlMail.cleanSource(newsletter)
        assertTrue(clean.contains("border-collapse"));assertTrue(clean.contains("<style>"))
        assertEquals("https://example.com/report?q=4&x=1",Jsoup.parse(clean).selectFirst("a")!!.attr("href"))
    }
    @Test fun activeContentLocalUrisAndUnsafeLinksAreRemoved() {
        val source="""<script>fetch('https://evil.test')</script><iframe src="https://evil.test"></iframe><form action="https://evil.test"><input value="secret"></form><a href="jav&#x61;script:alert(1)" onclick="evil()">Link</a><img src="file:///data/private" onerror="evil()"><svg onload="evil()"></svg>"""
        val rendered=HtmlMail.document(source,"",emptyList(),external=true)
        val doc=Jsoup.parse(rendered.html)
        assertTrue(doc.select("script,iframe,form,input,svg,[onclick],[onerror]").isEmpty())
        assertFalse(rendered.html.contains("file:///"));assertFalse(rendered.html.contains("javascript:"))
        assertTrue(rendered.images.isEmpty())
    }
    @Test fun externalImagesRequireConsentAndLeaveReadablePlaceholders() {
        val blocked=HtmlMail.document(newsletter,"",emptyList())
        assertTrue(blocked.externalImages);assertTrue(blocked.images.isEmpty())
        assertEquals("График",Jsoup.parse(blocked.html).selectFirst(".mail-image-placeholder")!!.text())
        val allowed=HtmlMail.document(newsletter,"",emptyList(),external=true)
        assertEquals(MailImageSource.Remote("https://images.example.com/hero.png"),allowed.images.values.single())
        assertTrue(Jsoup.parse(allowed.html).selectFirst("img")!!.attr("src").startsWith(HtmlMail.ORIGIN))
    }
    @Test fun cidImagesResolveWithoutEnablingExternalImages() {
        val attachment=Attachment("1.2","photo.png","image/png",100,"logo+1@example")
        val doc=HtmlMail.document("<p>Фото</p><img src='cid:logo%2B1%40example'>","",listOf(attachment))
        assertFalse(doc.externalImages);assertEquals(MailImageSource.Inline(attachment),doc.images.values.single())
    }
    @Test fun unknownCidAndTrackingPixelsDoNotProduceBrokenImageObjects() {
        val doc=HtmlMail.document("<img src='cid:missing' alt='Фото'><img src='https://x.example/pixel' width='1' height='1'>","",emptyList())
        assertTrue(Jsoup.parse(doc.html).select("img").isEmpty())
        assertTrue(Jsoup.parse(doc.html).text().contains("Фото"));assertFalse(doc.externalImages)
    }
    @Test fun cssBackgroundsUseTheSameConsentAndIsolatedResourceMap() {
        val source="<div style=\"background-image:url('https://img.example/a.png')\">Привет</div>"
        val blocked=HtmlMail.document(source,"",emptyList())
        assertTrue(blocked.externalImages);assertTrue(blocked.images.isEmpty())
        val allowed=HtmlMail.document(source,"",emptyList(),external=true)
        assertEquals(1,allowed.images.size)
        assertFalse(Jsoup.parse(allowed.html).selectFirst("div")!!.attr("style").contains("img.example"))
    }
    @Test fun standardQuotedRepliesStayInTheExistingCollapsedHistory() {
        val doc=HtmlMail.document("<p>Новый ответ</p><div class='gmail_attr'>Someone wrote:</div><div class='gmail_quote'>Старое письмо</div>","Новый ответ",emptyList(),hasHistory=true)
        val text=Jsoup.parse(doc.html).body().text()
        assertTrue(text.contains("Новый ответ"));assertFalse(text.contains("Старое письмо"))
    }
    @Test fun uncertainQuoteBoundariesUseTheCurrentTextWithoutDuplicatingHistory() {
        val doc=HtmlMail.document("<p>Ответ и старое письмо без структурной границы</p>","Только ответ",emptyList(),hasHistory=true)
        assertEquals("Только ответ",Jsoup.parse(doc.html).body().text())
    }
    @Test fun unsafeNavigationAndCredentialsAreRejectedWhileOrdinaryLinksWork() {
        listOf("javascript:alert(1)","intent://x","file:///x","content://x","data:text/html,x","https://name:pass@example.com/x","https://example.com/\nsecret").forEach{assertNull(HtmlMail.link(it))}
        assertEquals("https://example.com/report",HtmlMail.link("https://example.com/report"))
        assertEquals("mailto:client@example.com?subject=Заказ",HtmlMail.link("mailto:client@example.com?subject=Заказ"))
    }
    @Test fun plainEmailsKeepClickableUrlsAndEscapeHtmlLiterals() {
        val source="Адрес: https://example.com/a?b=1&c=2.\nКод: <script>example</script>"
        val doc=Jsoup.parse(HtmlMail.plain(source))
        assertTrue(doc.select("script").isEmpty())
        assertEquals("https://example.com/a?b=1&c=2",doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.text().contains("<script>example</script>"))
    }
    @Test fun imageResourceUrlsCannotBeReusedAcrossMessagesOrConsentChanges() {
        val source="<img src='cid:x'><img src='https://img.example/x.png'>"
        val attachment=Attachment("1","x.png","image/png",10,"x")
        val first=HtmlMail.document(source,"",listOf(attachment),identity="message-a")
        val second=HtmlMail.document(source,"",listOf(attachment),identity="message-b")
        val allowed=HtmlMail.document(source,"",listOf(attachment),external=true,identity="message-a")
        assertTrue(first.images.keys.intersect(second.images.keys).isEmpty())
        assertTrue(first.images.keys.intersect(allowed.images.keys).isEmpty())
    }
    @Test fun inlineDataImagesAreBoundedAndUnsafeDataTypesAreRemoved() {
        val good=HtmlMail.document("<img src='data:image/png;base64,AQID'>","",emptyList())
        assertEquals(1,good.images.size)
        val bad=HtmlMail.document("<img src='data:text/html;base64,PHNjcmlwdD4='>","",emptyList())
        assertTrue(bad.images.isEmpty())
    }
    @Test fun privateNetworkImagesCannotUseTheRemoteImageLoader() {
        listOf("127.0.0.1","10.0.0.2","172.16.0.1","192.168.1.2","169.254.1.1","100.64.1.1","::1","fc00::1").forEach{assertFalse(it,MailImageLimits.publicAddress(InetAddress.getByName(it)))}
        assertTrue(MailImageLimits.publicAddress(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun anOversizedInlineImageStopsBeforeItsBytesReachTheOutput() {
        val saved=ByteArrayOutputStream();val stream=BoundedMailOutput(saved,4)
        stream.write(byteArrayOf(1,2,3));assertThrows(java.io.IOException::class.java){stream.write(byteArrayOf(4,5))}
        assertArrayEquals(byteArrayOf(1,2,3),saved.toByteArray())
    }
}
