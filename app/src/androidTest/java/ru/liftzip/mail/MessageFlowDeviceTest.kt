package ru.liftzip.mail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
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

    @Test fun readerDisplaysCompactHeaderAndExpandableDetails() {
        val m=mail.copy(sender="\\\"Компания, Иван\\\"",cc="copy@example.com",subject="Очень длинная тема письма ".repeat(10))
        rule.setContent {MailTheme {ReaderScreen(m,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("unused")},onLink={},onDownload={},onOpenAttachment={},onOpenLocal={},onReply={},onForward={})}}
        rule.onNodeWithText("\"Компания, Иван\"").assertIsDisplayed()
        rule.onAllNodesWithText("Кому:",substring=true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Показать сведения").performClick()
        rule.onNodeWithText("Кому: a@example.com",substring=true).assertIsDisplayed()
        rule.onNodeWithText("Копия: copy@example.com",substring=true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Свернуть сведения").performClick()
        rule.onAllNodesWithText("Кому:",substring=true).assertCountEquals(0)
    }

    @Test fun selectingAnotherAccountKeepsDrawerOpenAndShowsItsFolders() {
        var selected by mutableStateOf<String?>(null)
        val other=account.copy(id="b",name="Личный",email="b@example.com")
        val data=UiState(loaded=true,accounts=listOf(account,other),folders=listOf(ServerFolder("a","FolderA","Папка A"),ServerFolder("b","FolderB","Папка B")))
        rule.setContent {
            val holder=rememberSaveableStateHolder();val drawer=rememberDrawerState(DrawerValue.Closed)
            val memories=remember{mutableMapOf<String,InboxMemory>()}
            val key=selected?:"all";val memory=memories.getOrPut(key){InboxMemory()}
            MailTheme {holder.SaveableStateProvider(key){InboxScreen(data,selected,MailFolder.INBOX,null,memory,drawerState=drawer,onAccount={selected=it},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})}}
        }
        rule.onNodeWithContentDescription("Выбрать ящик").performClick()
        rule.onNodeWithTag("drawer-account:a").performClick();rule.onNodeWithText("Папка A").assertIsDisplayed()
        rule.onNodeWithTag("drawer-account:b").performClick();rule.onNodeWithText("Папка B").assertIsDisplayed()
        rule.onNodeWithTag("drawer-account:b").performClick();rule.onNodeWithText("Папки на сервере").assertIsNotDisplayed()
        rule.onNodeWithContentDescription("Выбрать ящик").performClick();rule.onNodeWithText("Все ящики").performClick()
        rule.runOnIdle{assertNull(selected)}
        rule.onAllNodesWithText("Папки на сервере").assertCountEquals(0)
    }

    @Test fun multipleRecipientsExpandAndCollapseWithoutLosingAddresses() {
        rule.setContent {MailTheme {RecipientSummary("m","А <a@example.com>, Б <b@example.com>, В <c@example.com>","b@example.com")}}
        rule.onNodeWithText("b@example.com",substring=true).assertIsDisplayed()
        rule.onAllNodesWithText("a@example.com",substring=true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Показать всех получателей").performClick()
        rule.onNodeWithText("a@example.com",substring=true).assertIsDisplayed()
        rule.onNodeWithText("c@example.com",substring=true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Свернуть получателей").performClick()
        rule.onNodeWithText("b@example.com",substring=true).assertIsDisplayed()
        rule.onAllNodesWithText("a@example.com",substring=true).assertCountEquals(0)
    }

    @Test fun androidBackFromLetterRestoresTheScrolledMessageList() {
        var route by mutableStateOf("inbox")
        val memory=InboxMemory()
        val messages=(0..59).map{i->mail.copy(id="scroll-$i",subject="Сообщение $i",date=2_000_000L-i)}
        val state=UiState(loaded=true,accounts=listOf(account),messages=messages)
        rule.setContent {
            val holder=rememberSaveableStateHolder()
            MailTheme {
                RouteBackHandler(route,onPreview={},onMessage={route="inbox"},onCompose={},onHome={route="inbox"})
                if(route=="inbox")holder.SaveableStateProvider("inbox") {InboxScreen(state,"a",MailFolder.INBOX,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={route="reader"})}
                else Text("Открытое письмо")
            }
        }
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:scroll-0").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("inbox-list").performScrollToIndex(20)
        var index=0;var offset=0
        rule.runOnIdle{index=memory.list.firstVisibleItemIndex;offset=memory.list.firstVisibleItemScrollOffset}
        assertTrue("The fixture must be scrolled",index>=18)
        rule.onNodeWithTag("mail-row:scroll-20").performClick()
        rule.onNodeWithText("Открытое письмо").assertIsDisplayed()
        back()
        rule.waitUntil(15000){memory.restore==null && memory.list.firstVisibleItemIndex==index}
        rule.runOnIdle{assertEquals(index,memory.list.firstVisibleItemIndex);assertEquals(offset,memory.list.firstVisibleItemScrollOffset)}
        rule.onNodeWithTag("mail-row:scroll-20").assertIsDisplayed()
    }

    @Test fun hiddenPanelsAndExactScreenPositionSurviveAndroidBack() {
        var route by mutableStateOf("inbox")
        val memory=InboxMemory()
        val messages=(0..79).map{i->mail.copy(id="hidden-$i",subject="Письмо $i",date=2_000_000L-i)}
        val state=UiState(loaded=true,accounts=listOf(account),messages=messages)
        rule.setContent {
            val holder=rememberSaveableStateHolder()
            MailTheme(MailStyle.COZY) {
                RouteBackHandler(route,onPreview={},onMessage={route="inbox"},onCompose={},onHome={route="inbox"})
                if(route=="inbox")holder.SaveableStateProvider("inbox") {InboxScreen(state,"a",MailFolder.INBOX,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={route="reader"})}
                else Text("Открытое письмо")
            }
        }
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:hidden-0").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("inbox-list").performScrollToIndex(20)
        rule.onNodeWithTag("inbox-list").performTouchInput{swipe(center,center+Offset(0f,-600f),600)}
        rule.waitUntil(10000){memory.controlsProgress.floatValue==0f}
        rule.waitForIdle()
        var id="";var top=0f
        rule.runOnIdle{id=memory.list.layoutInfo.visibleItemsInfo.first{it.key is String && it.offset>=0 && it.offset+it.size<=memory.list.layoutInfo.viewportEndOffset}.key as String}
        top=rule.onNodeWithTag("mail-row:$id").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("mail-row:$id").performClick();rule.onNodeWithText("Открытое письмо").assertIsDisplayed()
        back()
        rule.waitUntil(15000){memory.restore==null && rule.onAllNodesWithTag("mail-row:$id").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("inbox-header").assertIsNotDisplayed();rule.onNodeWithTag("inbox-bottom").assertIsNotDisplayed()
        assertEquals("The same message must return to the same screen position",top,rule.onNodeWithTag("mail-row:$id").fetchSemanticsNode().boundsInRoot.top,2f)
    }

    @Test fun readerUsesWhiteBackgroundAndFloatingActionsStayFixed() {
        var replies=0;var forwards=0
        val m=mail.copy(body=(1..100).joinToString("\n"){"Строка письма $it"})
        rule.setContent {MailTheme(MailStyle.COZY){ReaderScreen(m,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("unused")},onLink={},onDownload={},onOpenAttachment={},onOpenLocal={},onReply={replies++},onForward={forwards++})}}
        rule.waitUntil(30000){rule.onAllNodesWithTag("mail-body").fetchSemanticsNodes().isNotEmpty()}
        val pixels=rule.onRoot().captureToImage().toPixelMap()
        val canvas=pixels[2,pixels.height/2]
        assertTrue("Reader background must be white",canvas.red>0.98f && canvas.green>0.98f && canvas.blue>0.98f)
        val top=rule.onNodeWithContentDescription("Ответить или переслать").fetchSemanticsNode().boundsInRoot.top
        rule.onRoot().performTouchInput{swipe(center,center+Offset(0f,-500f),600)};rule.waitForIdle()
        assertEquals(top,rule.onNodeWithContentDescription("Ответить или переслать").fetchSemanticsNode().boundsInRoot.top,1f)
        rule.onNodeWithContentDescription("Ответить или переслать").performClick()
        rule.onNodeWithText("Переслать").assertIsDisplayed().performClick()
        rule.runOnIdle{assertEquals(1,forwards)}
        rule.onNodeWithContentDescription("Ответить или переслать").performClick()
        rule.onNodeWithText("Ответить").assertIsDisplayed().performClick()
        rule.runOnIdle{assertEquals(1,replies)}
    }

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
        val m=mail.copy(attachments=listOf(Attachment("1","схема.pdf","application/pdf",2048)))
        var opened=0;var downloaded=0
        rule.setContent{MailTheme{ReaderScreen(m,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("No inline images")},onLink={},onDownload={downloaded++},onOpenAttachment={opened++},onOpenLocal={},onReply={},onForward={})}}
        rule.waitUntil(30000){rule.onAllNodesWithTag("mail-body").fetchSemanticsNodes().isNotEmpty()}
        val attachment=rule.onNodeWithContentDescription("Вложение схема.pdf").fetchSemanticsNode().boundsInRoot
        val body=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot
        assertTrue("Attachments must precede the body",attachment.bottom<body.top)
        rule.onNodeWithText("PDF").assertIsDisplayed()
        rule.onNodeWithContentDescription("Вложение схема.pdf").performClick()
        rule.onNodeWithContentDescription("Сохранить схема.pdf").performClick()
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
        rule.onNodeWithText("Первый",substring=true).assertIsDisplayed()
        rule.onNodeWithText("Второй",substring=true).assertIsDisplayed()
        saveDeviceScreenshot(rule,"expanded-history")
    }
    @Test fun imageAttachmentsOpenAndDownloadFromCompactPreviews() {
        val image=Attachment("1","фото.png","application/octet-stream",4_372*1024)
        val m=mail.copy(attachments=listOf(image))
        val file=java.io.File(rule.activity.cacheDir,"thumbnail-test.png")
        android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.RED);file.outputStream().use{compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};recycle()}
        var opened=0;var downloaded=0
        rule.setContent{MailTheme{ReaderScreen(m,account,false,emptyList(),emptySet(),onBack={},onStar={},onInline={error("unused")},onLink={},onDownload={downloaded++},onOpenAttachment={opened++},onOpenLocal={},onReply={},onForward={},onThumbnail={OpenedAttachment(android.net.Uri.fromFile(file).toString(),"фото.png","image/png")})}}
        rule.waitUntil(15000){rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Миниатюра загружена")).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Открыть изображение фото.png").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Сохранить фото.png").performClick()
        rule.runOnIdle{assertEquals(1,opened);assertEquals(1,downloaded)}
        saveDeviceScreenshot(rule,"image-thumbnails")
        file.delete()
    }
}
