package ru.liftzip.mail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailNavigationDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val account=MailAccount("a","lift-zip","test@example.com",provider=Provider.REG,imapHost="mail.hosting.reg.ru",smtpHost="mail.hosting.reg.ru")
    private val message=MailItem("m","a",12,7,sender="Отправитель",senderEmail="sender@example.com",recipients="test@example.com",subject="Проверяемое письмо",body="Сохранённый текст",date=1,unread=true)
    private val folder=mutableStateOf(MailFolder.UNREAD)
    private val path=mutableStateOf<String?>(null)
    private var exits=0
    private var move: Pair<Set<String>,Boolean>?=null
    private fun screen() {
        rule.runOnUiThread{rule.activity.edgeMailWindow()}
        rule.setContent {
            val memory=remember{InboxMemory()}
            val junk=message.copy(id="junk",remoteFolder="Spam",localFolder="remote-junk")
            MailTheme(MailStyle.COZY) {InboxScreen(UiState(loaded=true,accounts=listOf(account),messages=listOf(message,junk),folders=listOf(ServerFolder("a","Spam","Spam",FolderRole.JUNK))),
                "a",folder.value,path.value,memory,onAccount={},onEnable={_,_->},onFolder={folder.value=it;path.value=null},onServerFolder={path.value=it.path},onQueue={},
                onAdd={},onExit={exits++},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={},
                onSpam={ids,restore,done->move=ids to restore;done(ids)})}
        }
        rule.waitForIdle()
    }
    private fun back(){rule.waitForIdle();rule.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()};rule.waitForIdle()}
    @Test fun backReturnsFromUnreadAndServerSpamToInboxBeforeAskingToExit() {
        screen();back()
        rule.runOnIdle{assertEquals(MailFolder.INBOX,folder.value);assertEquals(0,exits)}
        rule.onNodeWithText("Выйти из Почты?").assertDoesNotExist()
        back();rule.onNodeWithText("Выйти из Почты?").assertIsDisplayed()
        rule.onNodeWithText("Остаться").performClick()
        rule.onNodeWithContentDescription("Выбрать ящик").performClick()
        rule.onNodeWithText("Спам",substring=false).performClick()
        rule.waitForIdle()
        rule.runOnIdle{assertEquals("Spam",path.value)}
        back();rule.runOnIdle{assertNull(path.value);assertEquals(0,exits)}
        rule.onNodeWithText("Выйти из Почты?").assertDoesNotExist()
        back();rule.onNodeWithText("Выйти из Почты?").assertIsDisplayed()
        rule.onNodeWithText("Выйти",substring=false).performClick()
        rule.runOnIdle{assertEquals(1,exits)}
    }
    @Test fun backClosesTheDrawerAndSelectionBeforeLeavingTheFolder() {
        screen()
        rule.onNodeWithContentDescription("Выбрать ящик").performClick();back()
        rule.runOnIdle{assertEquals(MailFolder.UNREAD,folder.value)}
        rule.onNodeWithTag("mail-row:m").performTouchInput{longClick()}
        rule.onNodeWithContentDescription("Снять выделение").assertIsDisplayed()
        back();rule.onNodeWithContentDescription("Снять выделение").assertDoesNotExist()
        rule.runOnIdle{assertEquals(MailFolder.UNREAD,folder.value);assertEquals(0,exits)}
    }
    @Test fun selectionMenuMovesOnlyTheChosenMessageToSpamAndCanRestoreIt() {
        screen()
        rule.onNodeWithTag("mail-row:m").performTouchInput{longClick()}
        rule.onNodeWithContentDescription("Действия с выбранными письмами").performClick()
        rule.onNodeWithText("В спам",substring=false).performClick()
        rule.runOnIdle{assertEquals(setOf("m") to false,move);path.value="Spam";folder.value=MailFolder.INBOX}
        rule.onNodeWithTag("mail-row:junk").performTouchInput{longClick()}
        rule.onNodeWithContentDescription("Действия с выбранными письмами").performClick()
        rule.onNodeWithText("Не спам · во Входящие").performClick()
        rule.runOnIdle{assertEquals(setOf("junk") to true,move)}
        saveDeviceScreenshot(rule,"spam-actions")
    }
    @Test fun readerExposesSpamAndRestoreActions() {
        var requests=0
        val current=mutableStateOf(message)
        rule.setContent {MailTheme {ReaderScreen(current.value,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("No inline image")},onLink={},onDownload={},onOpenAttachment={},onOpenLocal={},onReply={},onForward={},onSpam={requests++})}}
        rule.onNodeWithContentDescription("Действия с письмом").performClick()
        rule.onNodeWithText("В спам",substring=false).performClick()
        rule.runOnIdle{assertEquals(1,requests);current.value=message.copy(remoteFolder="Spam",localFolder="remote-junk")}
        rule.onNodeWithContentDescription("Действия с письмом").performClick()
        rule.onNodeWithText("Не спам · во Входящие").performClick()
        rule.runOnIdle{assertEquals(2,requests)}
    }
}
