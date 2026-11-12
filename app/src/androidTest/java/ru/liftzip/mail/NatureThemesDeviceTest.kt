package ru.liftzip.mail

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NatureThemesDeviceTest {
    @get:Rule val rule=createComposeRule()
    private val nature=listOf(MailStyle.FOREST,MailStyle.FROST,MailStyle.EMERALD,MailStyle.MEADOW)
    private val account=MailAccount("a","Тестовый ящик","test@example.com",provider=Provider.REG,imapHost="mail.example.com",smtpHost="mail.example.com")
    private val messages=(1..30).map { i->MailItem("nature-$i","a",sender="Отправитель $i",senderEmail="s@example.com",recipients="test@example.com",
        subject="Письмо $i",body="Сохранённый текст письма",date=1_800_000_000_000L,unread=i==2) }

    @Test fun themesKeepTheSameRowGeometryAndMailboxActions() {
        val style=mutableStateOf(MailStyle.BUSINESS)
        var opened="";var compose=0
        rule.setContent {
            val memory=remember{InboxMemory()}
            MailTheme(style.value) {
                InboxScreen(UiState(loaded=true,style=style.value,accounts=listOf(account),messages=messages),"a",MailFolder.INBOX,null,memory,
                    onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},
                    onRefresh={},onCompose={compose++},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={opened=it.id})
            }
        }
        rule.waitForIdle()
        val baseline=rule.onNodeWithTag("mail-row:nature-1").fetchSemanticsNode().boundsInRoot
        for(selected in listOf(MailStyle.BUSINESS)+nature) {
            rule.runOnIdle{style.value=selected};rule.waitForIdle()
            val bounds=rule.onNodeWithTag("mail-row:nature-1").fetchSemanticsNode().boundsInRoot
            assertEquals("Appearance must not rescale rows",baseline.width,bounds.width,1f)
            assertEquals("Appearance must not rescale rows",baseline.height,bounds.height,1f)
            saveDeviceScreenshot(rule,"style-${selected.key}")
            rule.onNodeWithTag("mail-row:nature-1").performClick()
            rule.runOnIdle{assertEquals("nature-1",opened);opened=""}
            rule.onNodeWithTag("mail-row:nature-1").performTouchInput{longClick()}
            rule.onNodeWithContentDescription("Удалить выбранные письма").assertIsDisplayed()
            rule.onNodeWithContentDescription("Снять выделение").performClick()
            rule.onNodeWithContentDescription("Написать письмо").performClick()
        }
        rule.runOnIdle{assertEquals(5,compose)}
    }

    @Test fun natureThemesStayLightWithDarkSystemAndPreserveTextScale() {
        val style=mutableStateOf(MailStyle.FOREST)
        var surface=0f;var ink=1f;var fontScale=0f;var originalFontScale=0f
        rule.setContent {
            val configuration=Configuration(LocalConfiguration.current).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            originalFontScale=LocalDensity.current.fontScale
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MailTheme(style.value) {
                    surface=MaterialTheme.colorScheme.surface.luminance()
                    ink=MaterialTheme.colorScheme.onSurface.luminance()
                    fontScale=LocalDensity.current.fontScale
                    DecorativeHeader("Входящие","Тестовый ящик",{})
                }
            }
        }
        for(selected in nature) {
            rule.runOnIdle{style.value=selected};rule.waitForIdle()
            rule.runOnIdle {
                assertTrue("Nature theme must remain light: $selected",surface>0.9f)
                assertTrue("Text must stay readable: $selected",ink<0.1f)
                assertEquals(originalFontScale,fontScale,0f)
            }
        }
    }

    @Test fun businessArtworkAtNarrowAndWideHeaderRatios() {
        rule.setContent {MailTheme(MailStyle.BUSINESS) {
            Column {
                DecorativeArtwork(MailStyle.BUSINESS,Modifier.width(240.dp).height(140.dp).testTag("narrow-desk"))
                DecorativeArtwork(MailStyle.BUSINESS,Modifier.fillMaxWidth().height(84.dp).testTag("wide-desk"))
            }
        }}
        rule.onNodeWithTag("narrow-desk").assertIsDisplayed()
        rule.onNodeWithTag("wide-desk").assertIsDisplayed()
        saveDeviceScreenshot(rule,"business-artwork-ratios")
    }
}
