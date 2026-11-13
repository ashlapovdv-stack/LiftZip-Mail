package ru.liftzip.mail

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val message=MailItem("reader-regression","a",sender="Sender",senderEmail="s@example.com",recipients="r@example.com",subject="Длинное письмо",body="Начало письма. Последняя строка письма.",date=1,
        htmlBody="<p>Начало письма</p><div style='height:100000px'></div><p>Последняя строка письма</p>",contentVersion=MailContent.FORMAT_VERSION)
    private fun webView(root: View=rule.activity.window.decorView): MailHtmlWebView? {
        if(root is MailHtmlWebView)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)webView(root.getChildAt(i))?.let{return it}
        return null
    }
    @Composable private fun reader(m: MailItem=message) {
        ReaderScreen(m,null,false,emptyList(),emptySet(),{},{},{error("No inline images")},{},{},{},{},{},{})
    }
    // The unified HTML reader renders both the current reply and quoted history
    // in the same native view. There must never be a second citation WebView.
    private fun unifiedHistoryView(): MailHtmlWebView {
        var views=emptyList<MailHtmlWebView>()
        rule.runOnIdle {
            fun collect(root: View,out: MutableList<MailHtmlWebView>) {
                if(root is MailHtmlWebView)out+=root
                if(root is ViewGroup)for(i in 0 until root.childCount)collect(root.getChildAt(i),out)
            }
            val found=mutableListOf<MailHtmlWebView>()
            collect(rule.activity.window.decorView,found)
            views=found
        }
        assertEquals("HTML history must share the main WebView",1,views.size)
        return views.single()
    }
    private fun loaded(): MailHtmlWebView {
        rule.waitForIdle()
        rule.waitUntil(60_000){var ready=false;rule.runOnUiThread{ready=webView()?.let{it.interactionEnabled && it.contentHeight>0 && it.height>20}?:false};ready}
        var view: MailHtmlWebView?=null;rule.runOnIdle{view=webView()};return view!!
    }
    private fun matches(view: MailHtmlWebView,text: String): Int {
        val count=AtomicInteger(-1)
        rule.runOnIdle{view.setFindListener{_,total,done->if(done)count.set(total)};view.findAllAsync(text)}
        rule.waitUntil(10_000){count.get()>=0};val found=count.get()
        rule.runOnIdle{view.setFindListener(null);view.clearMatches()}
        return found
    }
    private fun changeHistory(view: MailHtmlWebView,toggle: NativeHistoryControl,expanded: Boolean) {
        var revision=0L
        rule.runOnIdle{revision=view.documentGeneration()}
        toggle.performScrollTo().assertIsDisplayed().performClick()
        toggle.assertExpanded(expanded)
        rule.waitUntil(15_000) {
            var ready=false
            // Unified HTML history updates the document inside the SAME native WebView.
            // Plain history still changes only Compose controls, without a reload.
            rule.runOnUiThread{ready=view.documentGeneration()>=revision && view.interactionEnabled && view.progress==100}
            ready
        }
        rule.runOnIdle{android.util.Log.i("MailHistoryTest","expanded=$expanded generation=${view.documentGeneration()} url=${view.url} height=${view.contentHeight}")}
    }
    @Test fun plainOrderKeepsSeparateLinesAndTheFinalComment() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("plain-order.eml").use{input->
                MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),input))
            }
        assertTrue(mime.html.isBlank())
        rule.setContent{MailTheme{reader(message.copy(body=mime.text,htmlBody=""))}}
        val view=loaded()
        rule.waitUntil(15_000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        assertEquals(1,matches(view,"Комментарий к Вашему заказу"))
        assertEquals(1,matches(view,"Позвонить перед приездом."))
        rule.onNodeWithContentDescription("Оригинальный масштаб HTML").assertDoesNotExist()
        rule.runOnIdle{
            // The reader uses 16px / 1.55 line height. Count explicit source lines,
            // so a single wrapped paragraph cannot pass as a correctly laid out order.
            val lines=mime.text.trimEnd().lineSequence().count()
            assertTrue("Explicit plain-text lines must survive layout",view.contentHeight>=lines*24)
            assertFalse("Plain text must wrap within the reader",view.canScrollHorizontally(1))
        }
        saveDeviceScreenshot(rule,"plain-order")
        rule.runOnIdle{view.scrollContent(Int.MAX_VALUE)}
        rule.waitForIdle()
        saveDeviceScreenshot(rule,"plain-order-comment")
    }
    @Test fun historyBlocksExpandWithoutSourceDebugActions() {
        val source="Ответ\n\nOn 7 Nov 2026, sender@example.com wrote:\n> Старое письмо"
        val conversation=ConversationText.parse(source)
        rule.setContent{MailTheme{androidx.compose.foundation.layout.Column {ConversationHistory("blocks",conversation,emptyList())}}}
        val toggle=rule.onNodeWithContentDescription("Предыдущие сообщения: 1")
        rule.onNodeWithText("Старое письмо").assertDoesNotExist()
        toggle.performClick()
        rule.onNodeWithText("Старое письмо").assertExists()
        rule.onNodeWithText(source).assertDoesNotExist()
        rule.onNodeWithText("Показать полный исходный текст").assertDoesNotExist()
        rule.onNodeWithText("Показать оригинальное HTML-письмо").assertDoesNotExist()
        toggle.performClick()
        rule.onNodeWithText("Старое письмо").assertDoesNotExist()
    }
    @Test fun unavailableBodyShowsErrorWithoutPresentingThePreviewAsMail() {
        val m=message.copy(body="Текст из списка",htmlBody="",contentCached=false,previewOnly=false)
        var retries=0
        rule.setContent{MailTheme{ReaderScreen(m,null,false,emptyList(),emptySet(),{},{},{error("unused")},{},{},{},{},{},{},loadingError="Нет соединения",onRetryLoad={retries++})}}
        rule.onNodeWithText("Не удалось загрузить письмо: Нет соединения").assertIsDisplayed()
        rule.onNodeWithText("Открываем сохранённое письмо…").assertDoesNotExist()
        rule.onNodeWithTag("reader-opening-progress").assertDoesNotExist()
        rule.onAllNodesWithTag("mail-body").assertCountEquals(0)
        rule.onNodeWithText("Повторить").performClick()
        rule.runOnIdle{assertEquals(1,retries)}
    }
    @Test fun originalExportIsAvailableForServerMailWithoutSpamAction() {
        var exported=0
        val remote=message.copy(uid=42,validity=7)
        rule.setContent{MailTheme{ReaderScreen(remote,null,false,emptyList(),emptySet(),{},{},{error("unused")},{},{},{},{},{},{},onSaveOriginal={exported++})}}
        rule.onNodeWithContentDescription("Действия с письмом").performClick()
        rule.onNodeWithText("Сохранить оригинал (.eml)").assertIsEnabled().performClick()
        rule.runOnIdle{assertEquals(1,exported)}
        rule.onNodeWithText("Сохранить оригинал (.eml)").assertDoesNotExist()
    }
    @Test fun originalExportIsDisabledForLocalMail() {
        rule.setContent{MailTheme{ReaderScreen(message,null,false,emptyList(),emptySet(),{},{},{error("unused")},{},{},{},{},{},{},onSaveOriginal={fail("Local mail has no server original")})}}
        rule.onNodeWithContentDescription("Действия с письмом").performClick()
        rule.onNodeWithText("Сохранить оригинал (.eml)").assertIsNotEnabled()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }
    @Test fun linkedMimeHistoryExpandsAndCollapsesWithoutLosingRichContent() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("mailru-linked-history.eml").use{input->
                MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),input))
            }
        val conversation=ConversationText.parse(mime.text)
        val cached=message.copy(body=mime.text,htmlBody=mime.html,contentCached=true,uid=42,validity=7)
        rule.setContent{MailTheme{reader(cached)}}
        val view=loaded()
        val toggle=NativeHistoryControl(rule,"Предыдущие сообщения: ${conversation.messages.size}")
        toggle.performScrollTo().assertIsDisplayed()
        assertEquals(1,matches(view,"Текущий ответ"))
        assertEquals(0,matches(view,"Первое старое письмо"))
        changeHistory(view,toggle,true)
        val citation=unifiedHistoryView()
        assertEquals(1,matches(citation,"Первое старое письмо"))
        assertEquals(1,matches(citation,"Самое старое письмо"))
        assertEquals(1,matches(view,"Первое старое письмо"))
        rule.onNodeWithText("Показать полный исходный текст").assertDoesNotExist()
        rule.onNodeWithText("Показать оригинальное HTML-письмо").assertDoesNotExist()
        changeHistory(view,toggle,false)
        assertEquals(0,matches(view,"Первое старое письмо"))
        assertEquals(1,matches(view,"Текущий ответ"))
        saveDeviceScreenshot(rule,"linked-mime-history")
    }
    @Test fun longHtmlCitationSharesScrollingAndRevealsItsFinalLine() {
        val header="On 7 Nov 2026, sender@example.com wrote:"
        val old=(1..32).joinToString("\n"){"Строка цитаты $it"}+"\nПоследняя строка цитаты"
        val html="<p>Ответ</p><div>$header</div><blockquote>"+
            old.lineSequence().joinToString(""){"<p>$it</p>"}+"</blockquote>"
        val plain="Ответ\n\n$header\n"+old.lineSequence().joinToString("\n"){"> $it"}
        rule.setContent{MailTheme{reader(message.copy(id="long-citation",body=plain,htmlBody=html))}}
        val main=loaded()
        val toggle=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        changeHistory(main,toggle,true)
        val quote=unifiedHistoryView()
        assertEquals(1,matches(quote,"Последняя строка цитаты"))
        // Native scrolling must hand the movement back to the Compose page at its
        // bottom. A find match alone cannot prove that the final pixels are visible.
        rule.runOnIdle{assertNotNull("Citation must join the continuous reader scroll",quote.scrollPage)}
        repeat(16) {
            rule.onNodeWithTag("reader-viewport").performTouchInput {
                swipe(Offset(width*0.4f,height*0.75f),Offset(width*0.4f,height*0.25f),400)
            }
            rule.waitForIdle()
        }
        rule.runOnIdle {
            assertFalse("Native citation must reach its final line",quote.canScrollVertically(1))
            val visible=android.graphics.Rect()
            assertTrue(quote.getGlobalVisibleRect(visible))
            val position=IntArray(2);quote.getLocationOnScreen(position)
            assertTrue("The bottom of the native citation must be inside the visible reader",
                visible.height()>0)
            assertTrue(main.interactionEnabled)
        }
        rule.onNodeWithText("Показать полный исходный текст").assertDoesNotExist()
        rule.onNodeWithText("Показать оригинальное HTML-письмо").assertDoesNotExist()
        saveDeviceScreenshot(rule,"citation-final-line")
        // Return through the same native/Compose route a person uses. A semantic
        // jump to an off-screen control ignores the independently overlaid toolbar.
        repeat(16) {
            rule.onNodeWithTag("reader-viewport").performTouchInput {
                swipe(Offset(width*0.4f,height*0.3f),Offset(width*0.4f,height*0.8f),400)
            }
            rule.waitForIdle()
        }
        rule.runOnIdle{assertEquals(0,quote.scrollY)}
        changeHistory(main,toggle,false)
        assertEquals(1,matches(main,"Ответ"))
    }
    @Test fun reopeningUsesWarmGeometryAndStartsWithCollapsedHistory() {
        rule.runOnIdle{MailZoomDiagnostics.setEnabled(true)}
        try {
        ReaderDocuments.clear()
        var open by mutableStateOf(true)
        val body="Ответ\n\nOn 7 Nov 2026, sender@example.com wrote:\n> Старое письмо"
        val mail=message.copy(body=body,htmlBody="<p>Ответ</p><div>On 7 Nov 2026, sender@example.com wrote:</div><blockquote>Старое письмо</blockquote>")
        rule.setContent{MailTheme{if(open)reader(mail)}}
        val first=loaded();var cold=0L
        rule.runOnIdle{cold=first.documentGeneration()}
        val toggle=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        changeHistory(first,toggle,true)
        rule.runOnIdle{open=false};rule.waitForIdle();rule.runOnIdle{open=true}
        val second=loaded()
        rule.runOnIdle{
            val field=ReaderGeometryCache::class.java.getDeclaredField("entries").apply{isAccessible=true}
            val entries=field.get(ReaderDocuments.geometry) as Map<*,*>
            val dimensions=entries.keys.mapNotNull{it as? ReaderGeometryCache.Key}.map{"${it.width}x${it.viewport} density=${it.density} font=${it.fontScale} source=${it.document.html.hashCode()}"}
            android.util.Log.i("MailReaderReady","warm performance cold=$cold warm=${second.documentGeneration()} cache=$dimensions")
        }
        NativeHistoryControl(rule,"Предыдущие сообщения: 1").assertExpanded(false)
        rule.runOnIdle{assertNotSame(first,second);assertTrue("Warm open must skip the intrinsic measurement document: cold=$cold warm=${second.documentGeneration()}",second.documentGeneration()<cold)}
        assertEquals(0,matches(second,"Старое письмо"))
        saveDeviceScreenshot(rule,"reader-warm-open")
        } finally {rule.runOnIdle{MailZoomDiagnostics.setEnabled(false)}}
    }
    @Test fun plainAndHtmlReadersUseTheSameNeutralHistoryCapsule() {
        var html by mutableStateOf(false)
        val body="Ответ\n\nOn 7 Nov 2026, sender@example.com wrote:\n> Старое письмо"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=if(html)"<p>Ответ</p><div>On 7 Nov 2026, sender@example.com wrote:</div><blockquote>Старое письмо</blockquote>" else ""))}}
        val initial=loaded()
        var generation=0L
        rule.runOnIdle{generation=initial.documentGeneration()}
        NativeHistoryControl(rule,"Предыдущие сообщения: 1").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Показать переписку (1)").assertCountEquals(0)
        rule.runOnIdle{html=true}
        rule.waitUntil(30_000){
            var ready=false
            rule.runOnUiThread{ready=initial.documentGeneration()>generation && initial.interactionEnabled && initial.progress==100}
            ready && NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()
        }
        NativeHistoryControl(rule,"Предыдущие сообщения: 1").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Показать переписку (1)").assertCountEquals(0)
        val control=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        val before=control.fetchSemanticsNode().boundsInRoot
        changeHistory(initial,control,true)
        val after=control.fetchSemanticsNode().boundsInRoot
        assertEquals("Expanding must leave the control before the quote",before.top,after.top,3f)
        assertEquals(before.height,after.height,3f)
        loaded()
        saveDeviceScreenshot(rule,"history-unified-control")
    }
    @Test fun coldBodyFootersWaitForContentAndKeepTheSameGapAcrossSourceFormats() {
        var current by mutableStateOf<MailItem?>(null)
        rule.setContent{MailTheme{androidx.compose.foundation.layout.Column {
            current?.let{m->MailHtmlBody(m,m.body,HtmlMail.noHistory(m.htmlBody),{error("No images")},{},
                afterContent={HistoryToggle(1,false,{})})}
        }}}
        val cases=listOf("plain" to "","html" to "<div style='margin:0'>Ответ</div>",
            "padded" to "<div style='padding-bottom:36px'>Ответ</div>")
        val heights=mutableListOf<Float>()
        var density=1f
        rule.runOnIdle{density=rule.activity.resources.displayMetrics.density}
        cases.forEachIndexed{index,(name,html)->
            rule.runOnIdle{current=message.copy(id="footer-spacing-$index",body="Ответ",htmlBody=html)}
            rule.waitUntil(30_000){
                val nodes=rule.onAllNodes(hasTestTag("mail-body") or hasContentDescription("Предыдущие сообщения: 1"),useUnmergedTree=true).fetchSemanticsNodes()
                val body=nodes.any{it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)=="mail-body"}
                val footer=nodes.any{it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)}
                assertTrue("Following controls must not appear in the measurement window",!footer || body)
                body && footer
            }
            val body=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot
            val capsule=rule.onNodeWithTag("history-capsule",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals("The app's gap must not depend on MIME format",12*density,capsule.top-body.bottom,2f)
            heights+=body.height
            saveDeviceScreenshot(rule,"quote-spacing-$name")
            rule.runOnIdle{current=null};rule.waitForIdle()
        }
        assertEquals("Plain text and a margin-free HTML line have the same extent",heights[0],heights[1],2*density)
        assertTrue("Sender-defined bottom padding remains part of the HTML content",heights[2]>heights[1]+24*density)
    }
    @Test fun collapsedHistoryHasOneGapAfterPlainAndHtmlTrailingFormatting() {
        var current by mutableStateOf<MailItem?>(null)
        rule.setContent{MailTheme{androidx.compose.foundation.layout.Column {
            current?.let{m->
                val parsed=ConversationText.parse(m.body)
                val history=HtmlMail.analyzeHistory(m.htmlBody,parsed)
                assertTrue(history.separateHistory)
                MailHtmlBody(m,parsed.current,history,{error("No images")},{},afterContent={HistoryToggle(1,false,{})})
            }
        }}}
        val body="Ответ\n\nOn Thu, 12 Nov 2026, Person <p@example.com> wrote:\n> Earlier text"
        val suffix="<div class='gmail_attr'>On Thu, 12 Nov 2026, Person &lt;p@example.com&gt; wrote:</div><blockquote class='gmail_quote'><p>Earlier text</p></blockquote>"
        val cases=listOf("plain" to "","author-brs" to "<div>Ответ<div><br></div></div>",
            "paragraph-brs" to "<p style='margin-top:0'>Ответ</p><br><br>",
            "wrapper-padding" to "<div style='padding-bottom:40px;margin-bottom:24px'><p style='margin-top:0'>Ответ</p><div><br></div></div>")
        var baseline: Float?=null
        val density=rule.activity.resources.displayMetrics.density
        cases.forEachIndexed{index,(name,html)->
            rule.runOnIdle{current=message.copy(id="collapsed-gap-$index",body=body,htmlBody=if(html.isBlank())""else html+suffix)}
            rule.waitUntil(30_000){rule.onAllNodesWithTag("mail-body").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag("history-capsule",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            val bounds=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot
            val capsule=rule.onNodeWithTag("history-capsule",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals("Same visual gap for $name",12*density,capsule.top-bounds.bottom,2f)
            if(baseline==null)baseline=bounds.height else assertEquals("No invisible tail remains for $name",baseline!!,bounds.height,2*density)
            saveDeviceScreenshot(rule,"collapsed-gap-$name")
            rule.runOnIdle{current=null};rule.waitForIdle()
        }
    }
    // Exercise the production reader, including its header and bounded WebView.
    @Test fun realReaderFitsAWideNestedTableAtInitialScale() {
        val html="""<table style='width:2200px'><tr><td>Первый столбец</td><td><table style='width:900px'><tr><td>Последний столбец</td></tr></table></td></tr></table>"""
        rule.setContent{MailTheme{reader(message.copy(body="Первый столбец Последний столбец",htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        rule.waitUntil(15000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.waitForIdle()
        rule.runOnIdle {
            android.util.Log.i("MailReaderGeometry","width=${view.width} height=${view.height} contentHeight=${view.contentHeight} scale=${view.scale} x=${view.scrollX} y=${view.scrollY}")
            assertFalse("The complete table must fit initially",view.canScrollHorizontally(1))
        }
        rule.runOnIdle{view.zoomBy(2f)}
        rule.waitUntil(10000){rule.onAllNodesWithTag("reader-horizontal-scrollbar").fetchSemanticsNodes().isNotEmpty()}
        val bottom=rule.onNodeWithTag("reader-horizontal-scrollbar").fetchSemanticsNode().boundsInRoot.bottom
        rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-150f),300)}
        rule.waitForIdle()
        assertEquals(bottom,rule.onNodeWithTag("reader-horizontal-scrollbar").fetchSemanticsNode().boundsInRoot.bottom,1f)
    }
    @Test fun repeatedRealPinchesReturnHistoryToTheDocument() {
        val body="Ответ\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Предыдущий текст"
        val html="<p>Ответ</p><div class='gmail_quote'><p>Предыдущий текст</p></div>"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        rule.waitUntil(15000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        val original=history.fetchSemanticsNode().boundsInRoot.top
        repeat(10) { cycle->
            val center=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot.center
            rule.onRoot().performTouchInput {
                down(0,center-Offset(160f,0f));down(1,center+Offset(160f,0f))
                repeat(7) { step->
                    val distance=180f+step*20f
                    moveTo(0,center-Offset(distance,0f));moveTo(1,center+Offset(distance,0f));move()
                }
                up(1);up(0)
            }
            rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Вернуть исходный масштаб").fetchSemanticsNodes().isNotEmpty()}
            rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-100f),300)}
            rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
            rule.waitUntil(10000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
            rule.waitForIdle()
            val top=history.fetchSemanticsNode().boundsInRoot.top
            rule.runOnIdle{android.util.Log.i("MailReaderGeometry","cycle=$cycle viewHeight=${view.height} documentHeight=${view.contentHeight} scale=${view.scale} x=${view.scrollX} y=${view.scrollY} historyTop=$top")}
            assertEquals("History must not drift after pinch",original,top,8f*view.resources.displayMetrics.density)
        }
    }
    @Test fun currentBlockquoteSurvivesAndConfirmedHistoryExpandsWithoutDuplication() {
        val header="On Wed, 28 Oct 2026, Person <a@example.com> wrote:"
        val htmlHeader=header.replace("<","&lt;").replace(">","&gt;")
        val body="Новый ответ\nЦитата текущего ответа\n\n$header\n> Старое письмо"
        val html="<p>Новый ответ</p><blockquote><p>Цитата текущего ответа</p></blockquote><div class='gmail_attr'>$htmlHeader</div><div class='gmail_quote'><p>Старое письмо</p></div>"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        assertEquals(1,matches(view,"Цитата текущего ответа"));assertEquals(0,matches(view,"Старое письмо"))
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        history.assertIsDisplayed();changeHistory(view,history,true)
        assertEquals(1,matches(view,"Старое письмо"))
        assertEquals(1,matches(unifiedHistoryView(),"Старое письмо"))
        assertEquals(1,matches(view,"Цитата текущего ответа"))
        changeHistory(view,history,false)
        assertEquals(0,matches(view,"Старое письмо"))
        assertEquals(1,matches(view,"Цитата текущего ответа"))
    }
    @Test fun russianGenericNestedHistoryCanBeExpandedAndCollapsedInTheNativeReader() {
        val header="11 ноября 2026 г., 12:00 Person <a@example.com> писал:"
        val older="10 ноября 2026 г., 11:00 Other <b@example.com> писал:"
        val body="Текущий ответ\n\n$header\n> Первый ответ\n>\n> $older\n>> Второй ответ"
        val html="<p>Текущий ответ</p><div>"+header.replace("<","&lt;").replace(">","&gt;")+"</div>"+
            "<blockquote><p>Первый ответ</p><div>"+older.replace("<","&lt;").replace(">","&gt;")+"</div>"+
            "<blockquote>Второй ответ</blockquote></blockquote>"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        val toggle=NativeHistoryControl(rule,"Предыдущие сообщения: 2")
        toggle.assertIsDisplayed()
        assertEquals(1,matches(view,"Текущий ответ"));assertEquals(0,matches(view,"Первый ответ"))
        changeHistory(view,toggle,true)
        val citation=unifiedHistoryView()
        assertEquals(1,matches(citation,"Второй ответ"))
        assertEquals(1,matches(citation,"Первый ответ"))
        assertEquals(1,matches(view,"Первый ответ"));assertEquals(1,matches(view,"Текущий ответ"))
        changeHistory(view,toggle,false)
        assertEquals(0,matches(view,"Первый ответ"))
        assertEquals(1,matches(view,"Текущий ответ"))
    }
    @Test fun ambiguousHistoryBoundaryShowsFullHtmlWithoutADuplicatingPanel() {
        val header="On Wed, 28 Oct 2026, Person <a@example.com> wrote:"
        val body="Новый ответ\nОтвет после контейнера\n\n$header\n> Старое письмо"
        val html="<p>Новый ответ</p><div class='gmail_quote'><p>Старое письмо</p></div><p>Ответ после контейнера</p>"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        assertEquals(1,matches(view,"Новый ответ"));assertEquals(1,matches(view,"Старое письмо"))
        assertEquals(1,matches(view,"Ответ после контейнера"))
        rule.onAllNodesWithContentDescription("Предыдущие сообщения: 1").assertCountEquals(0)
    }
    // 0.5.19 regression: a small pinch that stays below the zoom-chrome threshold must
    // not move the history element or the window after the fingers are released.
    @Test fun smallPinchBelowZoomThresholdKeepsHistoryAndGeometryStable() {
        val body="Ответ\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Предыдущий текст"
        val html="<p>Ответ</p><div class='gmail_quote'><p>Предыдущий текст</p></div>"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        rule.waitUntil(15000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.waitUntil(15000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        val original=history.fetchSemanticsNode().boundsInRoot.top
        var initialHeight=0
        rule.runOnIdle{initialHeight=view.height}
        val tolerance=2f*view.resources.displayMetrics.density
        repeat(10) { cycle->
            val center=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot.center
            var scaleBefore=0f;rule.runOnIdle{scaleBefore=view.scale}
            // Exercise an actual sub-threshold WebView scale change. A 2px synthetic
            // finger movement is below Android's pinch recognition slop and produces
            // no scale callback, so it cannot test the geometry regression.
            rule.runOnIdle{view.zoomBy(1.025f)}
            rule.waitUntil(10000){var enlarged=false;rule.runOnUiThread{enlarged=view.scale>scaleBefore*1.005f};enlarged}
            // The gesture stayed below the zoom-chrome threshold.
            rule.onAllNodesWithContentDescription("Вернуть исходный масштаб").assertCountEquals(0)
            // Observe the resting layout, not the gesture itself: geometry must be frozen.
            rule.waitForIdle()
            Thread.sleep(1200)
            rule.waitForIdle()
            val top=history.fetchSemanticsNode().boundsInRoot.top
            rule.runOnIdle{android.util.Log.i("MailReaderGeometry","small cycle=$cycle viewHeight=${view.height} documentHeight=${view.contentHeight} scale=${view.scale} historyTop=$top")}
            assertEquals("History must not creep after a small pinch",original,top,tolerance)
            rule.runOnIdle{assertEquals("Window height must not track a small pinch",initialHeight,view.height)}
            rule.runOnIdle{view.resetZoom()}
            rule.waitUntil(10000){var restored=false;rule.runOnUiThread{restored=view.scale<scaleBefore*1.002f};restored}
            rule.waitForIdle()
            val returned=history.fetchSemanticsNode().boundsInRoot.top
            assertEquals("History must return together with the scale",original,returned,tolerance)
        }
    }
    @Test fun zoomingHtmlHidesAllChromeWithoutReplacingTheWebView() {
        val short=message.copy(htmlBody="<p>Текст письма для увеличения</p><img src='https://example.com/example.png'><table width='720'><tr><td>Содержимое</td></tr></table>",body="Текст письма для увеличения",attachments=listOf(Attachment("1","file.pdf","application/pdf",100)))
        rule.setContent{MailTheme{reader(short)}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        rule.onNodeWithContentDescription("Назад").assertIsDisplayed()
        rule.onNodeWithText("Длинное письмо").assertIsDisplayed()
        rule.onAllNodesWithText("По ширине").assertCountEquals(0)
        rule.runOnIdle{view.zoomBy(1.2f)}
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Назад").assertExists()
        rule.runOnIdle{view.zoomBy(2f)}
        rule.waitUntil(10000){!rule.onNodeWithContentDescription("Назад").isDisplayed()}
        rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
        rule.onNodeWithText("Длинное письмо").assertIsNotDisplayed()
        rule.onNodeWithText("Вложения").assertIsNotDisplayed()
        rule.onNodeWithContentDescription("Показать изображения").assertIsNotDisplayed()
        rule.onNodeWithContentDescription("Ответить или переслать").assertIsDisplayed()
        rule.runOnIdle{
            assertSame(view,webView())
            val location=IntArray(2);view.getLocationOnScreen(location)
            assertEquals("HTML must reach the left screen edge",0,location[0])
            assertEquals("HTML must reach the right screen edge",view.resources.displayMetrics.widthPixels,location[0]+view.width)
        }
        rule.onNodeWithContentDescription("Вернуть исходный масштаб").assertIsDisplayed().performClick()
        rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Длинное письмо").assertIsDisplayed()
        rule.runOnIdle{assertSame(view,webView())}
    }
    @Test fun plainHistoryControlRemainsVisibleWhenZoomHidesReaderChrome() {
        val body="Текущий ответ\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Старое письмо"
        rule.setContent{MailTheme{reader(message.copy(id="plain-history-zoom",body=body,htmlBody=""))}}
        val view=loaded()
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        history.performScrollTo().assertIsDisplayed()
        rule.runOnIdle{view.zoomBy(2f)}
        rule.waitUntil(10000){!rule.onNodeWithContentDescription("Назад").isDisplayed()}
        rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
        history.assertExists()
        history.performScrollTo().assertIsDisplayed()
        history.performClick()
        history.assertExpanded(true)
    }
    @Test fun htmlHistoryControlRemainsVisibleWhenZoomHidesReaderChrome() {
        val body="Текущий ответ\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Старое письмо"
        val html="<p>Текущий ответ</p><div class='gmail_attr'>On Wed, 28 Oct 2026, Person &lt;a@example.com&gt; wrote:</div><blockquote class='gmail_quote'><p>Старое письмо</p></blockquote>"
        rule.setContent{MailTheme{reader(message.copy(id="html-history-zoom",body=body,htmlBody=html))}}
        val view=loaded()
        rule.waitUntil(15_000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        history.performScrollTo().assertIsDisplayed()
        rule.runOnIdle{view.zoomBy(2f)}
        rule.waitUntil(10000){!rule.onNodeWithContentDescription("Назад").isDisplayed()}
        rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
        history.performScrollTo().assertIsDisplayed()
        changeHistory(view,history,true)
        assertEquals(1,matches(view,"Старое письмо"))
        history.performScrollTo().assertIsDisplayed()
        changeHistory(view,history,false)
        assertEquals(0,matches(view,"Старое письмо"))
    }
    @Test fun longPressCopiesFullSubjectWithoutOpeningIt() {
        val subject="Очень длинная тема письма ".repeat(12)
        rule.setContent{MailTheme{reader(message.copy(subject=subject,htmlBody="<p>Письмо</p>"))}}
        loaded()
        rule.waitUntil(15000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        waitForTestWindowFocus(rule,10000){var focused=false;rule.runOnUiThread{focused=rule.activity.hasWindowFocus()};focused}
        val node=rule.onNodeWithText(subject)
        node.performTouchInput{longClick()}
        rule.runOnIdle {
            val clipboard=rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(subject,clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        node.performClick();node.assertIsDisplayed()
    }
    @Test fun repeatedZoomAndResetKeepsHistoryNearTheLetter() {
        val body="Текущий текст\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Предыдущий текст"
        val short=message.copy(body=body,htmlBody="",subject="Письмо с историей")
        rule.setContent{MailTheme{reader(short)}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        rule.waitForIdle()
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        history.assertIsDisplayed()
        fun historyGap(): Float {
            var bottom=0
            rule.runOnIdle{val location=IntArray(2);view.getLocationInWindow(location);bottom=location[1]+view.height}
            return history.fetchSemanticsNode().boundsInRoot.top-bottom
        }
        val originalGap=historyGap()
        repeat(4) {
            rule.runOnIdle{view.zoomBy(2f)}
            rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Вернуть исходный масштаб").fetchSemanticsNodes().isNotEmpty()}
            rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-150f),300)}
            rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
            rule.waitUntil(10000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
            rule.waitForIdle()
            assertEquals("History must remain attached to the reading window after reset",originalGap,historyGap(),8f*view.resources.displayMetrics.density)
        }
    }
    @Test fun twoFingerGestureBelowShortMailUsesItsWebView() {
        val short=message.copy(body="Короткое письмо",htmlBody="<p>Короткое письмо</p>")
        rule.setContent{MailTheme{reader(short)}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        var before=0f;rule.runOnIdle{before=view.scale}
        rule.onRoot().performTouchInput {
            val y=height*0.75f
            down(0,Offset(center.x-35f,y));down(1,Offset(center.x+35f,y))
            moveTo(0,Offset(center.x-100f,y));moveTo(1,Offset(center.x+100f,y))
            move()
            up(1);up(0)
        }
        rule.waitUntil(10000){var enlarged=false;rule.runOnUiThread{enlarged=view.scale>before*1.05f};enlarged}
        rule.runOnIdle{assertSame(view,webView())}
    }
    @Test fun edgeDrawerBackKeepsReaderAndWebView() {
        val account=MailAccount("a","Рабочая","a@example.com",provider=Provider.REG,imapHost="example.com",smtpHost="example.com")
        val short=message.copy(body="Короткое письмо",htmlBody="<p>Короткое письмо</p>")
        val drawer=DrawerState(DrawerValue.Closed)
        rule.setContent {MailTheme {ReaderDrawer(UiState(loaded=true,accounts=listOf(account)),"a",null,
            onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onSettings={},drawerState=drawer){reader(short)}}}
        val view=loaded()
        val x=24f*view.resources.displayMetrics.density
        rule.onRoot().performTouchInput{swipe(Offset(x,height*0.7f),Offset(x+150f,height*0.7f),400)}
        rule.waitUntil(10000){drawer.currentValue==DrawerValue.Open}
        rule.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.waitUntil(10000){drawer.currentValue==DrawerValue.Closed}
        rule.runOnIdle{assertSame(view,webView())}
        rule.onRoot().performTouchInput{swipe(Offset(x,height*0.7f),Offset(x+150f,height*0.7f),400)}
        rule.waitUntil(10000){drawer.currentValue==DrawerValue.Open}
        rule.onRoot().performTouchInput{swipe(Offset(width*0.65f,height*0.5f),Offset(width*0.1f,height*0.5f),400)}
        rule.waitUntil(10000){drawer.currentValue==DrawerValue.Closed}
        rule.runOnIdle{assertSame(view,webView())}
    }
    @Test fun pinchOnHistoryDoesNotOpenIt() {
        val body="Текущий текст\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Предыдущий текст"
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=""))}}
        val view=loaded()
        rule.waitUntil(15000){var ready=false;rule.runOnUiThread{ready=view.progress==100};ready}
        val center=NativeHistoryControl(rule,"Предыдущие сообщения: 1").fetchSemanticsNode().boundsInRoot.center
        var before=0f;rule.runOnIdle{before=view.scale}
        rule.onRoot().performTouchInput {
            down(0,center-Offset(20f,0f));down(1,center+Offset(20f,0f))
            moveTo(0,center-Offset(80f,0f));moveTo(1,center+Offset(80f,0f));move()
            up(1);up(0)
        }
        rule.waitUntil(10000){var enlarged=false;rule.runOnUiThread{enlarged=view.scale>before*1.05f};enlarged}
        rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
        rule.waitUntil(10000){NativeHistoryControl(rule,"Предыдущие сообщения: 1").exists()}
        rule.onAllNodesWithText("Предыдущий текст",substring=true).assertCountEquals(0)
    }
    @Test fun aHundredThousandCssPixelLetterFitsAndStillScrollsToTheReplyControls() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.runOnIdle {
            assertTrue("The regression fixture must exceed the reported Compose limit at device scale",view.contentHeight*view.resources.displayMetrics.density>262143)
            assertTrue(view.height<=view.resources.displayMetrics.heightPixels)
            assertTrue(view.canScrollVertically(1))
        }
        rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-500f),600)}
        rule.waitUntil(5000){var moved=false;rule.runOnUiThread{moved=view.scrollY>0};moved}
        rule.onRoot().performTouchInput{down(center);up()}
        rule.runOnIdle{view.scrollContent(Int.MAX_VALUE);assertFalse(view.canScrollVertically(1))}
        rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-450f),600)}
        rule.waitForIdle();rule.onNodeWithContentDescription("Ответить или переслать").assertIsDisplayed().performClick()
        rule.onNodeWithText("Переслать").assertIsDisplayed();rule.onNodeWithContentDescription("Закрыть действия").performClick()
        saveDeviceScreenshot(rule,"html-long")
        rule.runOnIdle{view.scrollContent(Int.MIN_VALUE)}
        // Returning also traverses the footer and header, which together exceed 500 physical
        // pixels on the high-density Pixel 6. Use a screen-relative gesture in both densities.
        rule.onRoot().performTouchInput{swipe(Offset(center.x,height*0.15f),Offset(center.x,height*0.85f),600)}
        rule.waitForIdle();saveDeviceScreenshot(rule,"html-return")
        rule.waitForIdle();rule.onNodeWithText("Длинное письмо").assertIsDisplayed()
    }
    @Test fun closingAndReopeningCachedMailReleasesOldViewsAndKeepsTheBodyAvailable() {
        val visible=mutableStateOf(true)
        val short=message.copy(htmlBody="<p>Сохранённый текст письма</p>",body="Сохранённый текст письма")
        rule.setContent{MailTheme{if(visible.value)reader(short)}}
        repeat(4) {
            val previous=loaded()
            rule.runOnIdle{visible.value=false};rule.waitForIdle()
            rule.runOnIdle{assertNull(previous.parent);visible.value=true}
            val next=loaded();assertNotSame(previous,next)
        }
        rule.onNodeWithContentDescription("Ответить или переслать").assertIsDisplayed()
    }
    @Test fun coldReaderPreparationKeepsNativeViewsOnTheMainLooper() {
        var current by mutableStateOf<MailItem?>(null)
        rule.setContent{MailTheme{current?.let{reader(it)}}}
        repeat(8) { index->
            // Each source has a new cache key, so both preparation jobs must complete.
            val text="Холодное открытие $index"
            rule.runOnIdle{current=message.copy(id="cold-main-$index",body=text,htmlBody="<p>$text</p>")}
            val view=loaded()
            rule.runOnIdle {
                assertSame(android.os.Looper.getMainLooper(),view.handler.looper)
                assertTrue(view.isAttachedToWindow)
                current=null
            }
            rule.waitForIdle()
            rule.runOnIdle{assertNull(view.parent)}
        }
    }
    private fun assertCompleteFallbackAfterHistoryCrash(expanded: Boolean,rich: Boolean=true) {
        val header="On Wed, 28 Oct 2026, Person <a@example.com> wrote:"
        val body="Текущий ответ до сбоя\n\n$header\n> Старое письмо до сбоя"
        val html=if(rich)"<p>Текущий ответ до сбоя</p><div class='gmail_attr'>"+
            header.replace("<","&lt;").replace(">","&gt;")+"</div><div class='gmail_quote'>Старое письмо до сбоя</div>" else ""
        rule.setContent{MailTheme{reader(message.copy(body=body,htmlBody=html))}}
        val view=loaded()
        val history=NativeHistoryControl(rule,"Предыдущие сообщения: 1")
        history.assertExists()
        if(expanded) {
            if(rich)changeHistory(view,history,true) else history.performScrollTo().performClick()
        }
        rule.runOnIdle{view.loadUrl("chrome://crash")}
        rule.waitUntil(15_000){rule.onAllNodesWithText("Показываем текстовую версию письма").fetchSemanticsNodes().isNotEmpty()}
        // The fallback owns the complete source, irrespective of quote visibility or format.
        rule.onAllNodesWithText("Текущий ответ до сбоя",substring=true).assertCountEquals(1)
        rule.onAllNodesWithText("Старое письмо до сбоя",substring=true).assertCountEquals(1)
        rule.onNodeWithText(body).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Предыдущие сообщения: 1").assertCountEquals(0)
        rule.runOnIdle{assertNull(webView())}
        rule.onNodeWithContentDescription("Ответить или переслать").performClick()
        rule.onNodeWithContentDescription("Ответить на письмо").assertIsDisplayed().assertHasClickAction()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("Ответить на письмо").fetchSemanticsNodes().isEmpty()}
        rule.onAllNodesWithContentDescription("Ответить на письмо").assertCountEquals(0)
    }
    @Test fun collapsedHtmlHistoryRemainsReadableAfterRendererCrash() {
        assertCompleteFallbackAfterHistoryCrash(expanded=false)
    }
    @Test fun expandedHtmlHistoryRemainsReadableAfterRendererCrash() {
        assertCompleteFallbackAfterHistoryCrash(expanded=true)
    }
    @Test fun plainHistoryIsNotDuplicatedInTheFullFallback() {
        assertCompleteFallbackAfterHistoryCrash(expanded=true,rich=false)
    }
    @Test fun losingTheWebRendererShowsSavedTextAndLeavesReplyControlsWorking() {
        val short=message.copy(htmlBody="<p>Текст после сбоя просмотрщика</p>",body="Текст после сбоя просмотрщика")
        rule.setContent{MailTheme{reader(short)}}
        val view=loaded()
        // Official WebView test hook: the renderer dies, while the app must stay alive.
        rule.runOnIdle{view.loadUrl("chrome://crash")}
        rule.waitUntil(15_000){rule.onAllNodesWithText("Показываем текстовую версию письма").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Текст после сбоя просмотрщика").assertIsDisplayed()
        rule.onNodeWithContentDescription("Ответить или переслать").performClick()
        rule.onNodeWithContentDescription("Ответить на письмо").assertIsDisplayed().assertHasClickAction()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000){rule.onAllNodesWithContentDescription("Ответить на письмо").fetchSemanticsNodes().isEmpty()}
        rule.onAllNodesWithContentDescription("Ответить на письмо").assertCountEquals(0)
        rule.runOnIdle{assertNull(webView())}
        saveDeviceScreenshot(rule,"html-fallback")
    }
    @Test fun pastedHtmlReplyFoldsTheOuterQuoteWithItsNestedTables() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("html-paste-reply.eml").use{MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),it))}
        val conversation=ConversationText.parse(mime.text)
        val m=message.copy(id="html-paste",body=mime.text,htmlBody=mime.html)
        rule.setContent{MailTheme{reader(m)}}
        val view=loaded()
        NativeHistoryControl(rule,"Предыдущие сообщения: ${conversation.messages.size}").performScrollTo().assertIsDisplayed()
        assertEquals(1,matches(view,"Пришло на нашу html"))
        assertEquals(0,matches(view,"Тест HTML-письма"))
        saveDeviceScreenshot(rule,"html-paste-folded")
    }
    @Test fun wideTableAndNowrapParagraphDoNotShrinkTheWholeHtmlPage() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("html-paste-reply.eml").use{MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),it))}
        val m=message.copy(id="html-paste-full",body=mime.text,htmlBody=mime.html)
        rule.setContent{MailTheme{MailHtmlBody(m,m.body,HtmlMail.noHistory(m.htmlBody),{error("No resources")},{})}}
        val view=loaded()
        rule.runOnIdle{assertTrue("Whole page was shrunk",view.scale>=view.resources.displayMetrics.density*0.9f)}
        assertEquals(1,matches(view,"Широкая строка"))
        saveDeviceScreenshot(rule,"html-paste-responsive")
    }

    @Test fun receivedRichReplyExpandsAsHtmlAndBothPresentationsCanBeToggled() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("rich-reply-display.eml").use{
            MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),it))}
        val conversation=ConversationText.parse(mime.text)
        rule.setContent{MailTheme{reader(message.copy(id="rich-reply",body=mime.text,htmlBody=mime.html))}}
        val main=loaded()
        val toggle=NativeHistoryControl(rule,"Предыдущие сообщения: ${conversation.messages.size}")
        changeHistory(main,toggle,true)
        val quote=unifiedHistoryView()
        assertEquals(1,matches(quote,"Тест HTML-письма"));assertEquals(1,matches(quote,"Контактор"))
        assertEquals(1,matches(main,"Контактор"))
        toggle.performScrollTo();saveDeviceScreenshot(rule,"rich-reply-expanded")
        var generation=0L;rule.runOnIdle{generation=quote.documentGeneration()}
        rule.onNodeWithContentDescription("Оригинальный масштаб HTML").performClick()
        rule.waitUntil(30_000){var ready=false;rule.runOnUiThread{ready=quote.documentGeneration()>generation && quote.interactionEnabled && quote.progress==100};ready}
        rule.runOnIdle {
            assertEquals(quote.resources.displayMetrics.density,quote.scale,0.05f)
            assertFalse(quote.settings.javaScriptEnabled)
            assertTrue("Original width must be available to pan",quote.canScrollHorizontally(1))
        }
        toggle.performScrollTo();saveDeviceScreenshot(rule,"rich-reply-original")
        rule.onNodeWithContentDescription("Подогнать HTML по ширине").performClick()
        rule.onNodeWithContentDescription("Оригинальный масштаб HTML").assertExists()
        assertEquals(1,matches(unifiedHistoryView(),"Контактор"))
        changeHistory(main,toggle,false)
        assertEquals(0,matches(main,"Контактор"))
    }
    @Test fun originalScaleControlIsAbsentForPlainMailAndRoundTripsAFixedCanvas() {
        var rich by mutableStateOf(false)
        val html="<div style='width:960px'><p>Fixed canvas</p><table width='960'><tr><td>First</td><td>Second</td></tr></table></div>"
        rule.setContent{MailTheme{reader(message.copy(id="scale-control",body="Fixed canvas",htmlBody=if(rich)html else ""))}}
        loaded();rule.onNodeWithContentDescription("Оригинальный масштаб HTML").assertDoesNotExist()
        rule.runOnIdle{rich=true}
        val view=loaded()
        rule.waitUntil(30_000){var ready=false;rule.runOnUiThread{ready=view.interactionEnabled && view.progress==100 && view.scale<view.resources.displayMetrics.density*0.8f};ready}
        var baseline=0f;var generation=0L
        rule.runOnIdle{baseline=view.scale;generation=view.documentGeneration()}
        rule.onNodeWithContentDescription("Оригинальный масштаб HTML").performClick()
        rule.waitUntil(30_000){var ready=false;rule.runOnUiThread{ready=view.documentGeneration()>generation && view.interactionEnabled && view.progress==100};ready}
        rule.runOnIdle{assertEquals(view.resources.displayMetrics.density,view.scale,0.05f);generation=view.documentGeneration()}
        rule.onNodeWithContentDescription("Подогнать HTML по ширине").performClick()
        rule.waitUntil(30_000){var ready=false;rule.runOnUiThread{ready=view.documentGeneration()>generation && view.interactionEnabled && view.progress==100};ready}
        rule.runOnIdle{assertEquals(baseline,view.scale,0.05f);assertFalse(view.settings.javaScriptEnabled)}
    }

}
