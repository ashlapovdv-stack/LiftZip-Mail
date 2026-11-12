package ru.liftzip.mail

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val a=MailAccount("a","Sender","a@example.com",provider=Provider.REG,imapHost="imap.example.com",smtpHost="smtp.example.com")
    private val b=a.copy(id="b",email="b@example.com")
    private val source=MailItem("source","a",sender="Клиент",senderEmail="client@example.com",recipients=a.email,subject="Тема",body="Старая переписка",date=1_800_000_000_000L,htmlBody="<table><tr><td>Старая переписка</td></tr></table>",headers=MessageHeaders("<source@example.com>"))
    @Test fun composerIsWhiteAndHasTopActionsInADarkTheme() {
        var draft by mutableStateOf(ComposeDraft(accountId=a.id))
        var sent=0
        rule.setContent{MaterialTheme(colorScheme=darkColorScheme()){
            ComposeScreen(draft,UiState(accounts=listOf(a,b)),{draft=it},{},{},{sent++},{},{},{})
        }}
        rule.onNodeWithContentDescription("Добавить вложения").assertIsDisplayed()
        rule.onNodeWithContentDescription("Отправить письмо").assertIsDisplayed()
        rule.onNodeWithTag("compose-Тема").performTextInput("Новая тема")
        waitForEditor()
        rule.onNodeWithTag("compose-body").performTextInput("Новый текст")
        val pixel=rule.onNodeWithTag("compose-screen").captureToImage().toPixelMap()[3,3]
        assertTrue(pixel.red>0.99 && pixel.green>0.99 && pixel.blue>0.99)
        rule.runOnIdle {
            val bars=androidx.core.view.WindowCompat.getInsetsController(rule.activity.window,rule.activity.window.decorView)
            assertTrue(bars.isAppearanceLightStatusBars);assertTrue(bars.isAppearanceLightNavigationBars)
        }
        saveDeviceScreenshot(rule,"compose-new")
        rule.onNodeWithContentDescription("Отправить письмо").performClick()
        rule.waitUntil(10_000){sent==1}
        rule.runOnIdle{assertEquals(1,sent);assertEquals("Новый текст",draft.body)}
    }
    @Test fun copiesAndSenderSurviveCollapsingFieldsAndSaving() {
        var draft by mutableStateOf(ComposeDraft(accountId=a.id,to="client@example.com",subject="Тема",body="Текст"))
        var saved: ComposeDraft?=null
        rule.setContent{MailTheme{
            ComposeScreen(draft,UiState(accounts=listOf(a,b)),{draft=it},{saved=draft},{},{},{},{},{})
        }}
        rule.onNodeWithContentDescription("Копия и скрытая копия").performClick()
        rule.onNodeWithTag("compose-Копия").performTextInput("copy@example.com")
        rule.onNodeWithTag("compose-Скр. копия").performTextInput("hidden@example.com")
        rule.onNodeWithContentDescription("Копия и скрытая копия").performClick()
        rule.onNodeWithTag("compose-Копия").assertDoesNotExist()
        rule.onNodeWithText(a.email).performClick()
        rule.onNodeWithText(b.email).performClick()
        rule.onNodeWithContentDescription("Копия и скрытая копия").performClick()
        rule.onNodeWithTag("compose-Копия").assertTextContains("copy@example.com")
        saveDeviceScreenshot(rule,"compose-copies")
        rule.onNodeWithContentDescription("Сохранить черновик и закрыть").performClick()
        rule.waitUntil(10_000){saved!=null}
        rule.runOnIdle{assertEquals(b.id,saved!!.accountId);assertEquals("copy@example.com",saved!!.cc);assertEquals("hidden@example.com",saved!!.bcc)}
    }
    @Test fun quoteStaysSeparateAndCanBeExcludedWithoutChangingSource() {
        val quote=ComposeContent.quote(source,"reply")
        var draft by mutableStateOf(ComposeDraft(accountId=a.id,to="client@example.com",subject="Re: Тема",body="Ответ",quote=quote))
        rule.setContent{MailTheme{ComposeScreen(draft,UiState(accounts=listOf(a)),{draft=it},{},{},{},{},{},{})}}
        rule.onNodeWithTag("compose-body").assertTextContains("Ответ")
        rule.onNodeWithText("Исключить цитату").performScrollTo().performClick()
        rule.runOnIdle{assertEquals("Ответ",draft.body);assertFalse(draft.quoteIncluded);assertEquals(quote,draft.quote)}
        rule.onNodeWithText("Включить цитату").performScrollTo().performClick()
        rule.onNodeWithText("Исходное HTML-оформление сохранено").assertExists()
        saveDeviceScreenshot(rule,"compose-reply")
        rule.runOnIdle{assertTrue(draft.quoteIncluded);assertEquals(source.htmlBody,draft.quote!!.html)}
    }
    @Test fun missingInlineResourcesBlockSendOnlyWhileTheirQuoteIsIncluded() {
        val file=ForwardFile("inline","photo.png","image/png",10,contentId="photo",status=ForwardStatus.ERROR)
        val plan=ForwardPlan("a","source",42,7,"INBOX",listOf(file))
        var draft by mutableStateOf(ComposeDraft(accountId=a.id,to="client@example.com",subject="Re: Тема",body="Ответ",quote=ComposeContent.quote(source,"reply"),forwarding=plan))
        rule.setContent{MailTheme{ComposeScreen(draft,UiState(accounts=listOf(a)),{draft=it},{},{},{},{},{},{})}}
        rule.onNodeWithContentDescription("Отправить письмо").assertIsNotEnabled()
        rule.onNodeWithText("Исключить цитату").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Отправить письмо").assertIsEnabled()
    }
    private fun editor(): ComposeEditorWebView? {
        fun find(v: android.view.View): ComposeEditorWebView? {
            if(v is ComposeEditorWebView)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        return find(rule.activity.window.decorView)
    }
    private fun waitForEditor(){rule.waitUntil(10_000){var ready=false;rule.runOnIdle{ready=editor()?.ready==true};ready}}
    private fun js(script: String) {
        var done=false
        rule.runOnIdle{editor()!!.evaluateJavascript(script){done=true}}
        rule.waitUntil(10_000){done}
    }
    @Test fun nativeHtmlClipboardPasteEditAndSavePreserveTableAndFormatting() {
        var draft by mutableStateOf(ComposeDraft(accountId=a.id,to="client@example.com",subject="HTML"))
        var saved: ComposeDraft?=null
        rule.setContent{MailTheme{ComposeScreen(draft,UiState(accounts=listOf(a)),{draft=it},{saved=it},{},{},{},{},{})}}
        waitForEditor()
        rule.runOnIdle {
            val clipboard=rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newHtmlText("test","Жирный Таблица","<b>Жирный</b><span style='color:red'>Красный</span><table border='1'><tr><td>Таблица</td><td>Цена</td></tr></table>"))
            editor()!!.requestFocus()
            assertTrue(editor()!!.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE,null))
        }
        rule.waitUntil(10_000){draft.bodyHtml?.contains("<table")==true}
        saveDeviceScreenshot(rule,"compose-rich-paste")
        js("const e=document.getElementById('editor');e.querySelector('td').textContent='Изменено';e.dispatchEvent(new Event('input',{bubbles:true}));")
        rule.onNodeWithContentDescription("Сохранить черновик и закрыть").performClick()
        rule.waitUntil(10_000){saved!=null}
        rule.runOnIdle {
            val html=org.jsoup.Jsoup.parse(saved!!.bodyHtml!!)
            assertEquals("Жирный",html.select("b").text())
            assertEquals("color:red",html.select("span").attr("style"))
            assertEquals("Изменено",html.select("td").first()!!.text())
            assertTrue(saved!!.body.contains("Изменено"))
        }
    }
    @Test fun replacingAllWithPlainClipboardDoesNotResurrectOldHtml() {
        var draft by mutableStateOf(ComposerHtml.replace(ComposeDraft(accountId=a.id),"<b>Старое</b><table><tr><td>Таблица</td></tr></table>"))
        rule.setContent{MailTheme{ComposeScreen(draft,UiState(accounts=listOf(a)),{draft=it},{},{},{},{},{},{})}}
        waitForEditor();js("window.LiftZipEditor.selectAll()")
        rule.runOnIdle {
            val clipboard=rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test","<literal> Новый текст"))
            assertTrue(editor()!!.pasteClipboard())
        }
        rule.waitUntil(10_000){draft.body.contains("Новый текст")}
        assertFalse(draft.bodyHtml,draft.bodyHtml!!.contains("<table"));assertFalse(draft.bodyHtml,draft.bodyHtml!!.contains("<literal>"))
        assertTrue(draft.body.contains("<literal>"))
    }

    @Test fun styledAndroidClipboardWithoutHtmlMimeStillPreservesItsSpans() {
        var draft by mutableStateOf(ComposeDraft(accountId=a.id))
        rule.setContent{MailTheme{ComposeScreen(draft,UiState(accounts=listOf(a)),{draft=it},{},{},{},{},{},{})}}
        waitForEditor()
        rule.runOnIdle {
            val text=android.text.SpannableString("Жирный")
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,text.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val clipboard=rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test",text))
            editor()!!.requestFocus();assertTrue(editor()!!.pasteClipboard())
        }
        rule.waitUntil(10_000){draft.bodyHtml?.contains("Жирный")==true}
        val doc=org.jsoup.Jsoup.parse(draft.bodyHtml!!)
        assertEquals("Жирный",doc.select("b,strong").text())
    }

}
