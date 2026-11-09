package ru.liftzip.mail

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private fun loaded(): MailHtmlWebView {
        rule.waitUntil(60_000){var ready=false;rule.runOnUiThread{ready=webView()?.let{it.contentHeight>0 && it.height>20}?:false};ready}
        var view: MailHtmlWebView?=null;rule.runOnIdle{view=webView()};return view!!
    }
    @Test fun zoomingHtmlHidesToolbarAndRetainsTheSameWebView() {
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
        rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().isEmpty()}
        rule.onAllNodesWithText("Длинное письмо").assertCountEquals(0)
        rule.onAllNodesWithText("Вложения").assertCountEquals(0)
        rule.onAllNodesWithText("Показать изображения").assertCountEquals(0)
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
    @Test fun longPressCopiesFullSubjectWithoutOpeningIt() {
        val subject="Очень длинная тема письма ".repeat(12)
        rule.setContent{MailTheme{reader(message.copy(subject=subject,htmlBody="<p>Письмо</p>"))}}
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
        val history=rule.onNodeWithContentDescription("Предыдущие сообщения: 1")
        history.assertIsDisplayed()
        val originalTop=history.fetchSemanticsNode().boundsInRoot.top
        repeat(2) {
            rule.runOnIdle{view.zoomBy(2f)}
            rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Вернуть исходный масштаб").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
            rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Предыдущие сообщения: 1").fetchSemanticsNodes().isNotEmpty()}
            rule.waitForIdle()
            assertEquals("History must return to the same position",originalTop,history.fetchSemanticsNode().boundsInRoot.top,8f*view.resources.displayMetrics.density)
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
        rule.runOnIdle{assertNull(webView())}
        saveDeviceScreenshot(rule,"html-fallback")
    }
}
