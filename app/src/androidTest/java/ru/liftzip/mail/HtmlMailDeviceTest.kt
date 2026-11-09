package ru.liftzip.mail

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HtmlMailDeviceTest {
    @get:Rule val rule=createComposeRule()
    private fun image(): MailImageData {
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN)}
        val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        bitmap.recycle();return MailImageData("image/png",bytes)
    }
    private fun greenPixels(): Int {
        val pixels=rule.onRoot().captureToImage().toPixelMap();var found=0
        for(y in 0 until pixels.height step 4)for(x in 0 until pixels.width step 4) {
            val color=pixels[x,y];if(color.green>0.9f && color.red<0.1f && color.blue<0.1f)found++
        }
        return found
    }
    private fun matches(view: MailHtmlWebView,text: String): Int {
        val count=AtomicInteger(-1)
        rule.runOnIdle{view.setFindListener{_,total,done->if(done)count.set(total)};view.findAllAsync(text)}
        rule.waitUntil(10_000){count.get()>=0};val found=count.get()
        rule.runOnIdle{view.setFindListener(null);view.clearMatches()}
        return found
    }
    @Test fun desktopTableFitsInitiallyAndCanReturnToWidthAfterZooming() {
        val c=ApplicationProvider.getApplicationContext<Context>();val loaded=AtomicBoolean()
        val source="""<table width='720' style='border-collapse:collapse'><tr><td colspan='3' style='padding:20px'><h2>Backend checks: All jobs have passed</h2></td></tr><tr><th width='100'>Status</th><th width='240'>Job</th><th width='380'>Annotations</th></tr><tr><td>Passed</td><td>Backend checks</td><td>No warnings or errors</td></tr></table>"""
        val doc=HtmlMail.document("<div style='padding:0 36px'>$source</div>","",emptyList());lateinit var view: MailHtmlWebView
        rule.setContent {AndroidView(factory={MailHtmlWebView(c,{null},{},{},{loaded.set(true)}).also{view=it;it.show(doc)}},modifier=Modifier.fillMaxWidth().height(400.dp),onRelease={it.release()})}
        rule.waitUntil(15_000){loaded.get()}
        var scale=0f
        rule.runOnIdle {
            scale=view.scale
            assertFalse("The complete table must fit the initial viewport",view.canScrollHorizontally(1))
            assertTrue("Words must not form a many-screen vertical column",view.contentHeight*view.scale<=view.height*1.5f)
        }
        assertEquals(2,matches(view,"Backend checks"));saveDeviceScreenshot(rule,"html-fit")
        rule.runOnIdle{view.zoomBy(2f)}
        rule.waitUntil(5000){var zoomed=false;rule.runOnUiThread{zoomed=view.scale>scale*1.4f};zoomed}
        rule.runOnIdle{assertTrue(view.canScrollHorizontally(1));view.fitWidth()}
        rule.waitUntil(5000){var fit=false;rule.runOnUiThread{fit=kotlin.math.abs(view.scale-scale)<scale*0.05f};fit}
        rule.runOnIdle{assertFalse(view.canScrollHorizontally(1))}
    }
    @Test fun replacingDocumentIgnoresOldReadinessAndKeepsTheNewBody() {
        val c=ApplicationProvider.getApplicationContext<Context>();val ready=AtomicInteger()
        val first=HtmlMail.document("<p>Старый документ</p>","",emptyList(),identity="old")
        val second=HtmlMail.document("<p>Новый документ</p>","",emptyList(),identity="new")
        lateinit var view: MailHtmlWebView
        rule.setContent {AndroidView(factory={MailHtmlWebView(c,{null},{},{},{ready.incrementAndGet()}).also{
            view=it;it.show(first);it.show(second)
        }},modifier=Modifier.fillMaxWidth().height(300.dp),onRelease={it.release()})}
        rule.waitUntil(15000){ready.get()>0}
        assertEquals(1,matches(view,"Новый документ"));assertEquals(0,matches(view,"Старый документ"))
        assertEquals("Only the current document may report readiness",1,ready.get())
    }
    @Test fun newsletterCssIsHiddenInlineImageIsDrawnAndALinkOpensOnlyThroughTheApp() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val loaded=AtomicBoolean();val clicks=AtomicReference<String?>(null);val images=AtomicInteger();val png=image()
        val source="""<style>p{margin:0} @media screen and (max-width:600px){.section{width:100%!important}}</style><a href="https://example.com/report" style="display:block;height:48px;line-height:48px">Открыть прогноз</a><p>Аналитики поделились прогнозом на Q4</p><img src="cid:chart" width="40" height="40"><script>location='https://evil.example'</script><iframe src='https://evil.example'></iframe>"""
        val document=HtmlMail.document(source,"",listOf(Attachment("1","chart.png","image/png",100,"chart")))
        lateinit var view: MailHtmlWebView;var density=1f
        rule.setContent {
            density=LocalDensity.current.density
            AndroidView(factory={MailHtmlWebView(c,{images.incrementAndGet();png},{clicks.set(it)},{},{loaded.set(true)}).also{view=it;it.show(document)}},
                modifier=Modifier.fillMaxWidth().height(300.dp).testTag("mail-html"),onRelease={it.release()})
        }
        rule.waitUntil(15_000){loaded.get()}
        assertEquals(1,images.get());assertEquals(1,matches(view,"Аналитики"));assertEquals(0,matches(view,"important"));assertEquals(0,matches(view,"margin"))
        rule.waitUntil(10_000){greenPixels()>50}
        saveDeviceScreenshot(rule,"html-newsletter")
        rule.runOnIdle{assertFalse(view.settings.javaScriptEnabled);assertFalse(view.settings.allowFileAccess);assertFalse(view.settings.allowContentAccess);assertTrue(view.settings.blockNetworkLoads);assertFalse(view.settings.domStorageEnabled)}
        rule.onNodeWithTag("mail-html").performTouchInput{click(Offset(40*density,24*density))}
        rule.waitUntil(10_000){clicks.get()!=null};assertEquals("https://example.com/report",clicks.get())
        rule.runOnIdle{assertFalse(view.url.orEmpty().startsWith("https://example.com"))}
    }
    @Test fun aRemoteImageCannotReachTheLoaderUntilImagesAreEnabled() {
        val c=ApplicationProvider.getApplicationContext<Context>();val loaded=AtomicBoolean();val images=AtomicInteger();val png=image()
        val source="<p>Фото в письме</p><img src='https://images.example.com/chart.png' width='40' height='40' alt='График'>"
        val blocked=HtmlMail.document(source,"",emptyList())
        val allowed=HtmlMail.document(source,"",emptyList(),external=true)
        lateinit var view: MailHtmlWebView
        rule.setContent {AndroidView(factory={MailHtmlWebView(c,{images.incrementAndGet();png},{},{},{loaded.set(true)}).also{view=it;it.show(blocked)}},modifier=Modifier.fillMaxWidth().height(300.dp),onRelease={it.release()})}
        rule.waitUntil(15_000){loaded.get()};assertEquals(0,images.get());assertTrue(blocked.externalImages)
        rule.runOnIdle{loaded.set(false);view.show(allowed)}
        rule.waitUntil(15_000){loaded.get()};assertEquals(1,images.get())
        rule.waitUntil(10_000){greenPixels()>50};saveDeviceScreenshot(rule,"html-remote")
    }
    @Test fun lightHtmlCanvasMatchesTheSelectedTheme() {
        val c=ApplicationProvider.getApplicationContext<Context>();val png=image();val requests=AtomicInteger()
        val file=File(c.cacheDir,"theme-inline.png").apply{writeBytes(png.bytes)}
        val attachment=Attachment("1","chart.png","image/png",png.bytes.size,"chart")
        val message=MailItem("theme-html","a",sender="S",senderEmail="s@example.com",recipients="r@example.com",subject="Test",body="Текст",date=1,htmlBody="<p>Текст</p><img src='cid:chart'>",contentVersion=MailContent.FORMAT_VERSION,attachments=listOf(attachment))
        try {
            rule.setContent {MaterialTheme(colorScheme=lightColorScheme(surface=Color(0xFFFFF8EF))) {Column(Modifier.fillMaxSize().background(Color(0xFFFFF8EF)).verticalScroll(rememberScrollState())) {
                MailHtmlBody(message,message.body,false,onInline={requests.incrementAndGet();OpenedAttachment(Uri.fromFile(file).toString(),"chart.png","image/png")},onLink={})
            }}}
            rule.waitUntil(15_000){requests.get()==1};rule.waitUntil(10_000){greenPixels()>50}
            val pixels=rule.onRoot().captureToImage().toPixelMap()
            val canvas=pixels[pixels.width*3/4,(40*c.resources.displayMetrics.density).toInt()]
            saveDeviceScreenshot(rule,"html-warm-theme")
            assertTrue("HTML canvas must match the warm theme: $canvas",canvas.red>0.98f && canvas.green>0.95f && canvas.blue>0.91f && canvas.blue<0.96f)
        }finally{file.delete()}
    }
    @Test fun theProductionReaderLoadsAnInlineImageAndExpandsToItsActualHeight() {
        val c=ApplicationProvider.getApplicationContext<Context>();val png=image();val requests=AtomicInteger()
        val file=File(c.cacheDir,"html-inline-fixture.png").apply{writeBytes(png.bytes)}
        val attachment=Attachment("1","chart.png","image/png",png.bytes.size,"chart")
        val message=MailItem("html-native","a",sender="Sender",senderEmail="s@example.com",recipients="r@example.com",subject="Прогноз",body="Аналитики поделились прогнозом на Q4",date=1,
            htmlBody="<p>Аналитики поделились прогнозом на Q4</p><img src='cid:chart' width='40' height='40'>",contentVersion=MailContent.FORMAT_VERSION,attachments=listOf(attachment))
        try {
            rule.setContent {MaterialTheme(colorScheme=darkColorScheme(surface=Color(0xFF171B16),onSurface=Color.White)) {Column(Modifier.fillMaxSize().background(Color(0xFF171B16)).verticalScroll(rememberScrollState())) {
                MailHtmlBody(message,message.body,false,onInline={requests.incrementAndGet();OpenedAttachment(Uri.fromFile(file).toString(),"chart.png","image/png")},onLink={})
            }}}
            rule.waitUntil(15_000){requests.get()==1}
            rule.waitUntil(10_000){greenPixels()>50}
            rule.onNodeWithText("Показать изображения").assertDoesNotExist()
            val pixels=rule.onRoot().captureToImage().toPixelMap()
            val canvas=pixels[pixels.width-2,(40*c.resources.displayMetrics.density).toInt()]
            assertTrue("Rich mail must keep a readable light canvas in a dark app",canvas.red>0.9f && canvas.green>0.9f && canvas.blue>0.9f)
            saveDeviceScreenshot(rule,"html-inline")
        }finally{file.delete()}
    }
}
