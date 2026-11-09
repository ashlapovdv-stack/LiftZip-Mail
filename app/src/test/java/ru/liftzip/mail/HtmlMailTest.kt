package ru.liftzip.mail

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlMailTest {
    @Test fun neutralMessageBackgroundsBlendIntoThemeButColoredHighlightSurvives() {
        val html="""<body bgcolor="#ffffff"><p style="background:rgb(248,248,250)">Текст <span style="background-color:#fff">подпись</span><span style="background:#ffff00">важно</span></p><div style="background-color:#102030;color:white">тёмный блок</div><style>.note {background-color: whitesmoke;}</style></body>"""
        val doc=Jsoup.parse(HtmlMail.document(html,"",emptyList(),background="#fff8ef").html)
        assertTrue(doc.selectFirst("p")!!.attr("style").contains("background:transparent"))
        assertTrue(doc.selectFirst("span")!!.attr("style").contains("background-color:transparent"))
        assertTrue(doc.select("span")[1].attr("style").contains("#ffff00"))
        assertTrue(doc.select("div").any{it.attr("style").contains("#102030")})
        assertTrue(doc.select("style").any{it.data().contains("background-color: transparent")})
        assertTrue(doc.select("[bgcolor]").none{HtmlMail.neutralFill(it.attr("bgcolor"))})
    }
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
        val attachment=Attachment("1.2","photo.png","IMAGE/PNG",100,"logo+1@example")
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
    @Test fun uncertainQuoteBoundariesPreserveRichHtmlAndDoNotOfferDuplicatedHistory() {
        val source="<p>Ответ</p><table><tr><td>Старое письмо</td></tr></table>"
        val doc=HtmlMail.document(source,"Только ответ",emptyList(),hasHistory=true)
        assertEquals("Старое письмо",Jsoup.parse(doc.html).selectFirst("table td")!!.text())
        assertFalse(HtmlMail.canSeparateHtmlHistory(source))
        assertTrue(HtmlMail.canSeparateHtmlHistory("<p>Ответ</p><blockquote>Старое письмо</blockquote>"))
    }
    @Test fun htmlWithoutQuoteBoundaryKeepsItsFormattingAndMargins() {
        val doc=HtmlMail.document("<p style='margin:0'>Ответ без границы цитаты</p>","Только ответ",emptyList(),hasHistory=true)
        assertTrue(doc.html.contains("padding-left:16px;padding-right:16px"))
        assertEquals("Ответ без границы цитаты",Jsoup.parse(doc.html).body().text())
    }
    @Test fun zeroInnerMarginDoesNotRemovePageInset() {
        val doc=HtmlMail.document("<p style='margin:0'>Текст</p>","Текст",emptyList())
        assertTrue(doc.html.contains("padding-left:16px;padding-right:16px"))
    }
    @Test fun existingOuterPaddingIsNotDuplicated() {
        val doc=HtmlMail.document("<div style='padding:24px'>Текст</div>","Текст",emptyList())
        assertTrue(doc.html.contains("padding-left:0;padding-right:0"))
    }
    @Test fun verticalOnlyAndOneSidedInsetsDoNotRemovePageMargins() {
        listOf("padding:12px 0", "margin-top:12px").forEach { style ->
            val doc=HtmlMail.document("<div style='$style'>Текст</div>","Текст",emptyList())
            assertTrue(style,doc.html.contains("padding-left:16px;padding-right:16px"))
        }
        val oneSide=HtmlMail.document("<div style='padding-left:12px'>Текст</div>","Текст",emptyList())
        assertTrue(oneSide.html.contains("padding-left:0;padding-right:16px"))
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
        val extra=Jsoup.parse(HtmlMail.plain("(https://example.com/wiki_(Topic)) www.example.com client@example.com"))
        assertEquals(listOf("https://example.com/wiki_(Topic)","https://www.example.com","mailto:client@example.com"),extra.select("a").map{it.attr("href")})
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
    @Test fun senderBodyColorsAndLegacyFontFormattingSurviveTheBodyFragment() {
        val clean=HtmlMail.cleanSource("<html><body bgcolor='#000000' style='color:white' dir='rtl'><font color='white'>Visible</font></body></html>")
        val doc=Jsoup.parse(clean)
        val wrapper=doc.selectFirst("div")!!
        assertTrue(wrapper.attr("style").contains("color:white"));assertTrue(wrapper.attr("style").contains("background-color:#000000"))
        assertEquals("rtl",wrapper.attr("dir"));assertEquals("white",doc.selectFirst("font")!!.attr("color"))
    }

    @Test fun fractionalWebViewScalesCannotGrowTheViewportOnEveryMeasurement() {
        for(scale in listOf(1f,1.371234f,2.625f,3.5f)) {
            var pixels=HtmlMail.height(700,scale)
            val initial=pixels
            repeat(100){
                val css=kotlin.math.ceil(pixels.toDouble()/scale).toInt().coerceAtLeast(700)
                pixels=HtmlMail.height(css,scale)
            }
            assertEquals(initial,pixels)
        }
    }

    @Test fun quotedCssImageUrlsKeepTheirParentheses() {
        val source="<div style=\"background-image:url('https://img.example/hero_(Q4).png')\">Article</div>"
        val doc=HtmlMail.document(source,"",emptyList(),external=true)
        assertEquals(MailImageSource.Remote("https://img.example/hero_(Q4).png"),doc.images.values.single())
        assertFalse(Jsoup.parse(doc.html).selectFirst("div")!!.attr("style").contains(".png"))
    }
    @Test fun longMalformedCssCannotReachAnyImageSourceOrDestroyReadableBodyText() {
        val source="<div style=\"background:"+"url(".repeat(10_000)+"\">Readable body</div>"
        val doc=HtmlMail.document(source,"",emptyList(),external=true)
        assertTrue(doc.images.isEmpty());assertEquals("Readable body",Jsoup.parse(doc.html).body().text())
        assertEquals("background:none",Jsoup.parse(doc.html).selectFirst("div")!!.attr("style"))
    }

}
