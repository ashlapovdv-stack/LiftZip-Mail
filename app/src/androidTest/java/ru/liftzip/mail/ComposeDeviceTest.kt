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
        rule.onNodeWithTag("compose-body").performTextInput("Новый текст")
        val pixel=rule.onNodeWithTag("compose-screen").captureToImage().toPixelMap()[3,3]
        assertTrue(pixel.red>0.99 && pixel.green>0.99 && pixel.blue>0.99)
        rule.runOnIdle {
            val bars=androidx.core.view.WindowCompat.getInsetsController(rule.activity.window,rule.activity.window.decorView)
            assertTrue(bars.isAppearanceLightStatusBars);assertTrue(bars.isAppearanceLightNavigationBars)
        }
        saveDeviceScreenshot(rule,"compose-new")
        rule.onNodeWithContentDescription("Отправить письмо").performClick()
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
}
