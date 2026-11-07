package ru.liftzip.mail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageFlowDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val account=MailAccount("a","Рабочая","a@example.com",provider=Provider.REG,imapHost="mail.hosting.reg.ru",smtpHost="mail.hosting.reg.ru",colorSlot=2)
    private val mail=MailItem("m","a",12,7,sender="Клиент",senderEmail="client@example.com",recipients="a@example.com",subject="Тема письма",body="Основной текст",date=1)
    private fun back(){rule.waitForIdle();rule.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()};rule.waitForIdle()}

    @Test fun attachmentBackReturnsToLetterThenToTheSameServerFolder() {
        var route by mutableStateOf("inbox")
        var folder by mutableStateOf(MailFolder.INBOX)
        var serverPath by mutableStateOf<String?>(null)
        var exits=0
        val junk=mail.copy(id="junk",remoteFolder="Spam",localFolder="remote-junk")
        rule.setContent {
            val holder=rememberSaveableStateHolder()
            val memories=remember{mutableMapOf<String,InboxMemory>()}
            MailTheme(MailStyle.COZY) {
                RouteBackHandler(route,onPreview={route="reader"},onMessage={route="inbox"},onCompose={route="inbox"},onHome={serverPath=null;folder=MailFolder.INBOX;route="inbox"})
                when(route) {
                    "inbox" -> {
                        val key="${folder.name}:${serverPath.orEmpty()}"
                        val memory=memories.getOrPut(key){InboxMemory()}
                        holder.SaveableStateProvider(key){InboxScreen(UiState(loaded=true,accounts=listOf(account),messages=listOf(mail,junk),folders=listOf(ServerFolder("a","Spam","Spam",FolderRole.JUNK))),
                            "a",folder,serverPath,memory,onAccount={},onEnable={_,_->},onFolder={folder=it;serverPath=null},onServerFolder={serverPath=it.path},onQueue={},
                            onAdd={},onExit={exits++},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={route="reader"})}
                    }
                    "reader" -> Button(onClick={route="preview"}){Text("Открыть изображение")}
                    else -> Text("Просмотр вложения")
                }
            }
        }
        rule.onNodeWithContentDescription("Выбрать ящик").performClick()
        rule.onNodeWithText("Спам",substring=false).performClick()
        rule.onNodeWithTag("mail-row:junk").performClick()
        rule.onNodeWithText("Открыть изображение").performClick()
        rule.onNodeWithText("Просмотр вложения").assertIsDisplayed()
        back();rule.onNodeWithText("Открыть изображение").assertIsDisplayed()
        back();rule.onNodeWithTag("mail-row:junk").assertIsDisplayed()
        rule.runOnIdle{assertEquals("Spam",serverPath);assertEquals(0,exits)}
        rule.onNodeWithText("Папки на сервере").assertIsNotDisplayed()
        back();rule.runOnIdle{assertNull(serverPath);assertEquals(MailFolder.INBOX,folder);assertEquals(0,exits)}
        back();rule.onNodeWithText("Выйти из Почты?").assertIsDisplayed()
    }

    @Test fun compactAttachmentsAppearBeforeTheBodyAndKeepBothActions() {
        val m=mail.copy(attachments=listOf(Attachment("1","схема.png","image/png",2048)))
        var opened=0;var downloaded=0
        rule.setContent{MailTheme{ReaderScreen(m,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("No inline images")},onLink={},onDownload={downloaded++},onOpenAttachment={opened++},onOpenLocal={},onReply={},onForward={})}}
        rule.waitUntil(30000){rule.onAllNodesWithTag("mail-body").fetchSemanticsNodes().isNotEmpty()}
        val attachment=rule.onNodeWithContentDescription("Вложение схема.png").fetchSemanticsNode().boundsInRoot
        val body=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot
        assertTrue("Attachments must precede the body",attachment.bottom<body.top)
        rule.onNodeWithText("схема.png").performClick()
        rule.onNodeWithContentDescription("Сохранить схема.png").performClick()
        rule.runOnIdle{assertEquals(1,opened);assertEquals(1,downloaded)}
        saveDeviceScreenshot(rule,"compact-attachments")
    }

    @Test fun openingConversationExpandsEveryPreviousMessage() {
        val history=QuotedConversation("Текущий",listOf(QuotedMessage("Первый",null,"Содержимое первого"),QuotedMessage("Второй",null,"Содержимое второго")),"Оригинал")
        rule.setContent {MailTheme {Column {ConversationHistory("m",history,emptyList())}}}
        rule.onNodeWithText("Содержимое первого").assertDoesNotExist()
        rule.onNodeWithText("Предыдущие сообщения · 2").performClick()
        rule.onNodeWithText("Содержимое первого").assertIsDisplayed()
        rule.onNodeWithText("Содержимое второго").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Свернуть сообщение").assertCountEquals(2)
        saveDeviceScreenshot(rule,"expanded-history")
    }
}
