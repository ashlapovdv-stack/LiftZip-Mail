package ru.liftzip.mail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxSelectionDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val account=MailAccount("a","Тестовый ящик","test@example.com",provider=Provider.REG,
        imapHost="mail.hosting.reg.ru",smtpHost="mail.hosting.reg.ru")
    private fun subject(i: Int)="Письмо $i — длинная тема для проверки ширины текста"
    private fun snippet(i: Int)="Сохранённый текст черновика $i для проверки геометрии карточки."
    private fun row(i: Int)=rule.onNodeWithTag("mail-row:selection-$i")
    private fun geometry(i: Int)=listOf(row(i).fetchSemanticsNode().boundsInRoot,
        rule.onNodeWithText("Отправитель $i",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot,
        rule.onNodeWithText(subject(i),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot,
        rule.onNodeWithText(snippet(i),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot)
    private fun sameGeometry(expected: List<Rect>,actual: List<Rect>) {
        expected.zip(actual).forEach{(before,after)->
            assertEquals("Left edge moved",before.left,after.left,1f)
            assertEquals("Top edge moved",before.top,after.top,1f)
            assertEquals("Width changed",before.width,after.width,1f)
            assertEquals("Height changed",before.height,after.height,1f)
        }
    }
    private fun back() {
        rule.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.waitForIdle()
    }
    private fun checkSelection(style: MailStyle) {
        val count=mutableIntStateOf(5)
        val memory=InboxMemory()
        rule.runOnUiThread{rule.activity.edgeMailWindow()}
        rule.setContent {
            val messages=remember(count.intValue){(0 until count.intValue).map{i->
                MailItem("selection-$i","a",sender="Отправитель $i",senderEmail="sender@example.com",
                    recipients="test@example.com",subject=subject(i),body=snippet(i),
                    date=1_800_000_000_000L-i*60_000,localFolder="drafts")
            }}
            MailTheme(style){InboxScreen(UiState(loaded=true,style=style,accounts=listOf(account),messages=messages),
                null,MailFolder.DRAFTS,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},
                onAdd={},onExit={error("Selection must close before navigation")},onNoticeAnchor={},onSettings={},onDelivery={},
                onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={error("Long press must select")})}
        }
        rule.waitUntil(15_000){rule.onAllNodesWithTag("mail-row:selection-1").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
        val first=geometry(0);val second=geometry(1)
        val initialPosition=memory.position()
        saveDeviceScreenshot(rule,"selection-${style.key}-before")
        row(1).performTouchInput{longClick()}
        rule.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        sameGeometry(first,geometry(0));sameGeometry(second,geometry(1))
        assertEquals(initialPosition,memory.position())
        row(0).performClick()
        rule.onNodeWithText("Выбрано: 2").assertIsDisplayed()
        sameGeometry(first,geometry(0));sameGeometry(second,geometry(1))
        saveDeviceScreenshot(rule,"selection-${style.key}-active")
        rule.onNodeWithContentDescription("Снять выделение").performClick()
        sameGeometry(first,geometry(0));sameGeometry(second,geometry(1))
        assertEquals(initialPosition,memory.position())

        rule.runOnIdle{count.intValue=100}
        rule.waitUntil(15_000){memory.list.layoutInfo.totalItemsCount==100}
        rule.waitForIdle()
        rule.runOnIdle{memory.list.requestScrollToItem(8,31);memory.controlsProgress.floatValue=0.45f}
        rule.waitForIdle()
        val partial=geometry(10);val partialPosition=memory.position()
        row(10).performTouchInput{longClick()}
        rule.waitForIdle()
        assertEquals(0.45f,memory.controlsProgress.floatValue,0f)
        sameGeometry(partial,geometry(10));assertEquals(partialPosition,memory.position())
        back()
        assertEquals(0.45f,memory.controlsProgress.floatValue,0f)
        sameGeometry(partial,geometry(10));assertEquals(partialPosition,memory.position())

        rule.runOnIdle{memory.controlsProgress.floatValue=0f}
        rule.waitForIdle()
        val hidden=geometry(10);val hiddenPosition=memory.position()
        row(10).performTouchInput{longClick()}
        rule.waitForIdle()
        assertEquals(0f,memory.controlsProgress.floatValue,0f)
        sameGeometry(hidden,geometry(10));assertEquals(hiddenPosition,memory.position())
        rule.onNodeWithTag("inbox-list").performTouchInput{swipeDown(durationMillis=600)}
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Снять выделение").assertIsDisplayed()
        val restoredPosition=memory.position()
        back()
        assertEquals(restoredPosition,memory.position())
        rule.onNodeWithContentDescription("Снять выделение").assertDoesNotExist()

        rule.runOnIdle{memory.list.requestScrollToItem(99);memory.controlsProgress.floatValue=1f}
        rule.waitForIdle()
        val last=geometry(99);val endPosition=memory.position()
        row(99).performTouchInput{longClick()}
        sameGeometry(last,geometry(99));assertEquals(endPosition,memory.position())
        back()
        sameGeometry(last,geometry(99));assertEquals(endPosition,memory.position())
    }
    @Test fun standardSelectionKeepsRowsAndScrollPosition()=checkSelection(MailStyle.STANDARD)
    @Test fun cozySelectionKeepsRowsAndScrollPosition()=checkSelection(MailStyle.COZY)
    @Test fun businessSelectionKeepsRowsAndScrollPosition()=checkSelection(MailStyle.BUSINESS)
}
