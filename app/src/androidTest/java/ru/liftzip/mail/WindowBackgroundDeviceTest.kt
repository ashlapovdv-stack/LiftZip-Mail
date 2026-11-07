package ru.liftzip.mail

import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=30)
class WindowBackgroundDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val style=mutableStateOf(MailStyle.COZY)
    private var canvas=0
    private var refreshed=0
    private var settings=0
    private val refreshing=mutableStateOf(false)
    private val account=MailAccount("a","lift-zip","test@example.com",provider=Provider.REG,imapHost="mail.hosting.reg.ru",smtpHost="mail.hosting.reg.ru")
    private val messages=(1..30).map{i->MailItem("edge-$i","a",sender="Отправитель $i",senderEmail="sender@example.com",recipients="test@example.com",subject="Письмо $i",body="Сохранённый текст письма",date=1_800_000_000_000L,unread=i==1)}
    private fun screen() {
        val activity=rule.activity
        rule.runOnUiThread {
        activity.edgeMailWindow()
        activity.setContent {
            MailTheme(style.value) {
                canvas=MaterialTheme.colorScheme.surface.toArgb()
                key(style.value) {
                val memory=remember{InboxMemory()}
                InboxScreen(UiState(loaded=true,accounts=listOf(account),messages=messages,refreshing=refreshing.value),"a",MailFolder.INBOX,null,memory,
                    onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},
                    onSettings={settings++},onDelivery={},onRefresh={refreshed++;refreshing.value=true},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})
                }
            }
        }
        }
        rule.waitForIdle()
    }
    private fun command(text: String)=ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(text)).bufferedReader().use{it.readText()}
    private fun currentOverlay(): String?=command("cmd overlay list --user 0").lineSequence()
        .map{it.trim()}.firstOrNull{it.startsWith("[x] com.android.internal.systemui.navbar.")}?.substringAfter("[x] ")
    private fun close(a: Int,b: Int)=kotlin.math.abs(Color.red(a)-Color.red(b))<=4 &&
        kotlin.math.abs(Color.green(a)-Color.green(b))<=4 && kotlin.math.abs(Color.blue(a)-Color.blue(b))<=4
    @Test fun backgroundReachesSystemBarsWithButtonsAndGestures() {
        val original=currentOverlay();screen()
        try {
            for((mode,overlay) in listOf("buttons" to "threebutton","gestures" to "gestural")) {
                val result=command("cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.$overlay")
                assertFalse(result,result.contains("Error",true))
                rule.waitUntil(15000){currentOverlay()?.endsWith(".$overlay")==true}
                // Framework overlay changes can recreate the plain test host. Production
                // MainActivity configures every new window in onCreate; reproduce that here.
                rule.activityRule.scenario.recreate()
                screen()
                for(selected in MailStyle.entries) {
                    rule.runOnIdle{style.value=selected};rule.waitForIdle()
                    rule.onNodeWithContentDescription("Настройки").assertDoesNotExist()
                    rule.onNodeWithContentDescription("Обновить почту").assertDoesNotExist()
                    // Insets and SystemUI composition settle independently of the Compose clock.
                    var top=0;var bottom=0;var contrast=true
                    rule.runOnIdle {
                        val insets=rule.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
                        top=insets.top;bottom=insets.bottom
                        contrast=rule.activity.window.isNavigationBarContrastEnforced
                    }
                    assertTrue(top>0);assertTrue(bottom>0)
                    rule.waitUntil(15000) {
                        val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        if(shot==null)false else try{close(canvas,shot.getPixel(shot.width/8,shot.height-bottom/2))}finally{shot.recycle()}
                    }
                    // Window insets and screenshots use window coordinates, which may differ
                    // from the Compose root after an Android navigation-mode change.
                    val menu=rule.onNodeWithContentDescription("Выбрать ящик").fetchSemanticsNode().boundsInWindow
                    val tabs=rule.onNodeWithContentDescription("Черновики").fetchSemanticsNode().boundsInWindow
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No full-window screenshot")
                    try {
                        saveDeviceBitmap(bitmap,"edge-${selected.key}-$mode")
                        assertFalse("Navigation transparency lost in $mode/$selected",contrast)
                        assertTrue("Menu must stay below system icons",menu.top>=top)
                        assertTrue("Tabs must stay above system navigation",tabs.bottom<=bitmap.height-bottom)
                        assertTrue("System navigation must reveal the app canvas",close(canvas,bitmap.getPixel(bitmap.width/8,bitmap.height-bottom/2)))
                        val status=bitmap.getPixel((bitmap.width*0.42f).toInt(),top/2)
                        if(selected==MailStyle.STANDARD)assertTrue(close(canvas,status))
                        else {
                            val samples=listOf(0.25f,0.3f,0.4f,0.6f).map{bitmap.getPixel((bitmap.width*it).toInt(),top/2)}
                            assertTrue("Status area must show artwork, not a solid strip or camera cutout",samples.any{!close(canvas,it) && Color.blue(it)>60})
                        }
                    }finally{bitmap.recycle()}
                    repeat(2){rule.onRoot().performTouchInput{swipe(Offset(center.x,height*0.8f),Offset(center.x,height*0.2f),400)};rule.waitForIdle()}
                    rule.onNodeWithTag("inbox-header").assertIsNotDisplayed()
                    rule.onNodeWithTag("inbox-bottom").assertIsNotDisplayed()
                    val list=rule.onNodeWithTag("inbox-list").fetchSemanticsNode().boundsInRoot
                    val full=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:error("No hidden window screenshot")
                    try {
                        assertEquals("List must reach the top physical edge",0f,list.top,1f)
                        assertEquals("List must reach the bottom physical edge",full.height.toFloat(),list.bottom,1f)
                        saveDeviceBitmap(full,"edge-hidden-${selected.key}-$mode")
                    }finally{full.recycle()}
                }
            }
        }finally{if(original!=null)command("cmd overlay enable-exclusive --user 0 --category $original")}
    }
    @Test fun toolbarButtonsAreGoneWhileDrawerSettingsAndPullRefreshStillWork() {
        screen()
        rule.onNodeWithContentDescription("Настройки").assertDoesNotExist()
        rule.onNodeWithContentDescription("Обновить почту").assertDoesNotExist()
        rule.onRoot().performTouchInput{swipe(Offset(center.x,height*0.45f),Offset(center.x,height*0.9f),700)}
        rule.waitUntil(5000){refreshed==1}
        rule.onNodeWithContentDescription("Выбрать ящик").performClick()
        rule.onNodeWithText("Настройки").performClick()
        rule.runOnIdle{assertEquals(1,settings)}
    }
}
