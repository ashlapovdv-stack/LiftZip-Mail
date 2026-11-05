package ru.liftzip.mail

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceDeviceTest {
    @get:Rule val rule=createComposeRule()
    private val account=MailAccount("a","Gmail","test@example.com",provider=Provider.GMAIL,imapHost="imap.gmail.com",smtpHost="smtp.gmail.com")
    private fun state(style: MailStyle)=UiState(loaded=true,style=style,accounts=listOf(account),messages=(1..30).map{i->
        MailItem("theme-$i","a",sender=if(i<=2)"Google"else "Отправитель $i",senderEmail="sender@example.com",recipients="test@example.com",
            subject=if(i==1)"Оповещение системы безопасности"else "Письмо $i",body="Текст письма и важные сведения для получателя.",date=1_800_000_000_000L-i*60_000,unread=i==3)
    },history=mapOf("a" to InboxHistory(30,false,1)))
    private fun inbox(style: MailStyle,onCompose: ()->Unit={},onOpen: (MailItem)->Unit={}) {
        rule.setContent{
            val memory=remember{InboxMemory()};var folder by remember{mutableStateOf(MailFolder.INBOX)}
            MailTheme(style){InboxScreen(state(style),null,folder,null,memory,onAccount={},onEnable={_,_->},onFolder={folder=it},onServerFolder={},onQueue={},
                onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose=onCompose,onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen=onOpen)}
        }
    }
    @Test fun cozyCardsKeepSelectionAndComposeActions() {
        var compose=0;inbox(MailStyle.COZY,onCompose={compose++});rule.waitForIdle()
        rule.onNodeWithText("Оповещение системы безопасности").assertIsDisplayed()
        saveDeviceScreenshot(rule,"style-cozy")
        rule.onNodeWithText("Оповещение системы безопасности").performTouchInput{longClick()}
        rule.onNodeWithContentDescription("Удалить выбранные письма").assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithContentDescription("Снять выделение").performClick()
        rule.onNodeWithContentDescription("Написать письмо").performClick();rule.runOnIdle{assertEquals(1,compose)}
    }
    @Test fun businessCardsKeepOpeningMessagesAndUnreadNavigation() {
        var opened="";inbox(MailStyle.BUSINESS,onOpen={opened=it.id});rule.waitForIdle()
        saveDeviceScreenshot(rule,"style-business")
        rule.onNodeWithText("Оповещение системы безопасности").performClick();rule.runOnIdle{assertEquals("theme-1",opened)}
        rule.onNodeWithContentDescription("Непрочитанные").performClick();rule.waitForIdle()
        rule.onNodeWithText("Непрочитанные").assertIsDisplayed();rule.onNodeWithText("Письмо 3").assertIsDisplayed()
    }
    @Test fun appearanceChoicePersistsAndLeavesOtherSettingsUnchanged() {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="appearance-test-"+System.nanoTime()
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        try {
            prefs.edit().putBoolean("auto-hide-panels",false).commit();assertEquals(MailStyle.STANDARD,AppearancePreferences.read(prefs))
            val current=mutableStateOf(AppearancePreferences.read(prefs));var visible by mutableStateOf(true)
            rule.setContent{MailTheme(current.value){if(visible)AppearanceSettingsScreen(state(current.value),{}, {},{AppearancePreferences.write(prefs,it);current.value=it})}}
            rule.onNodeWithText("Уютный").performClick();rule.runOnIdle{assertEquals(MailStyle.COZY,AppearancePreferences.read(prefs))}
            rule.onNodeWithText("Деловой").performScrollTo().performClick();rule.waitForIdle()
            saveDeviceScreenshot(rule,"appearance")
            rule.runOnIdle{visible=false;prefs.edit().putBoolean("saved",true).commit();current.value=AppearancePreferences.read(context.getSharedPreferences(name,Context.MODE_PRIVATE));visible=true}
            rule.waitForIdle();rule.runOnIdle {
                assertEquals(MailStyle.BUSINESS,current.value);assertFalse(prefs.getBoolean("auto-hide-panels",true))
                assertTrue(File(context.applicationInfo.dataDir,"shared_prefs/$name.xml").readText().contains("business"))
            }
        }finally{context.deleteSharedPreferences(name)}
    }
}
