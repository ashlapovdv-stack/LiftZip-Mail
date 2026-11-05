package ru.liftzip.mail

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailScreensDeviceTest {
    @get:Rule val rule=createComposeRule()
    private val account=MailAccount(id="a",name="lift-zip",email="test@example.com",provider=Provider.REG,imapHost="mail.hosting.reg.ru",smtpHost="mail.hosting.reg.ru")
    private fun state()=UiState(loaded=true,accounts=listOf(account),messages=(1..30).map{i->MailItem("m$i","a",i.toLong(),7,sender="Отправитель $i",senderEmail="sender@example.com",recipients="test@example.com",subject="Запрос по оборудованию $i",body="Добрый день. Прошу сообщить срок поставки и стоимость.",date=1_800_000_000_000L-i*60_000,unread=i<4)},history=mapOf("a" to InboxHistory(30,false,3)),folders=listOf(ServerFolder("a","INBOX","INBOX",FolderRole.INBOX,30,3),ServerFolder("a","Sent","Sent",FolderRole.SENT,8,0),ServerFolder("a","Drafts","Drafts",FolderRole.DRAFTS,2,0),ServerFolder("a","Trash","Trash",FolderRole.TRASH,5,0)))
    private fun screen() {
        rule.setContent {
            val memory=remember{InboxMemory()}
            MailTheme {InboxScreen(state(),"a",MailFolder.INBOX,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},
                onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})}
        }
        rule.waitForIdle()
    }
    private fun screenshot(name: String)=saveDeviceScreenshot(rule,name)
    @Test fun mailboxMenusAndUnreadCountsRemainAccessible() {
        screen();rule.onNodeWithContentDescription("Непрочитанные").assertIsDisplayed()
        screenshot("inbox")
        rule.onNodeWithContentDescription("Выбрать ящик").performClick()
        rule.onNodeWithContentDescription("lift-zip: непрочитанных 3").assertIsDisplayed()
        rule.onNodeWithText("Папки на сервере").assertIsDisplayed()
        rule.onNodeWithText("Корзина").assertIsDisplayed()
        screenshot("drawer")
    }
    @Test fun singleUnreadMessageRestoresMenusAfterLeavingAPartlyHiddenInbox() {
        var composeRequests=0
        val folder=mutableStateOf(MailFolder.INBOX)
        val model=state().copy(messages=state().messages.mapIndexed{i,m->m.copy(unread=i==0)})
        rule.setContent {
            val memory=remember{InboxMemory()}
            MailTheme {InboxScreen(model,"a",folder.value,null,memory,onAccount={},onEnable={_,_->},onFolder={folder.value=it},onServerFolder={},onQueue={},
                onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={composeRequests++},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})}
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-100f),600)}
        rule.waitForIdle();screenshot("mail-partial")
        rule.onNodeWithContentDescription("Непрочитанные").performClick()
        rule.waitForIdle()
        screenshot("single-unread")
        rule.onNodeWithText("Поиск писем").assertIsDisplayed()
        rule.onNodeWithContentDescription("Выбрать ящик").assertIsDisplayed()
        rule.onNodeWithContentDescription("Написать письмо").assertIsDisplayed().assertHasClickAction().performClick()
        rule.runOnIdle{assertEquals(1,composeRequests)}
        rule.onNodeWithText("Запрос по оборудованию 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Отправленные").performClick();rule.waitForIdle()
        rule.onAllNodesWithText("Загружено",substring=true).assertCountEquals(0)
    }
    @Test fun uncertainSendingRequiresAnExplicitConfirmation() {
        var retries=0
        val a=MailAction("send","a","m1",ActionKind.SEND,"{}",subject="Проверка отправки",status="uncertain",error="Соединение прервалось после передачи письма.")
        rule.setContent{MailTheme{ActionsScreen(state().copy(actions=listOf(a)),onBack={},onRetry={_,_->retries++},onCancel={},onAccounts={})}}
        rule.onNodeWithText("Доставка не подтверждена").assertIsDisplayed();screenshot("queue")
        rule.onNodeWithText("Проверил, повторить").performClick();rule.runOnIdle{assertEquals(0,retries)}
        rule.onNodeWithText("Отправить ещё раз").performClick();rule.runOnIdle{assertEquals(1,retries)}
    }
}

internal fun saveDeviceScreenshot(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule,name: String) {
    saveDeviceBitmap(rule.onRoot().captureToImage().asAndroidBitmap(),name)
}
internal fun saveDeviceBitmap(bitmap: Bitmap,name: String) {
    val c=ApplicationProvider.getApplicationContext<Context>()
    val file=File(c.filesDir,"ui-checks/$name.png");file.parentFile!!.mkdirs()
    file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    // Export while the target APK is still installed: Gradle can remove it after the suite.
    val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/liftzip-ui-checks")).use{it.readBytes()}
    val pipes=automation.executeShellCommandRw("dd of=/data/local/tmp/liftzip-ui-checks/$name.png bs=65536")
    ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{output->file.inputStream().use{it.copyTo(output)}}
    ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use{it.readBytes()}

}
