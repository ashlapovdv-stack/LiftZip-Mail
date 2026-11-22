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

    @Test fun arrivalsRespectPositionAndReturnToTop() {
        val memory=InboxMemory()
        var messages by mutableStateOf((0..59).map{i->mail.copy(id="arrive-$i",subject="Письмо $i",date=2_000_000L-i)})
        rule.setContent {MailTheme {InboxScreen(UiState(loaded=true,accounts=listOf(account),messages=messages),"a",MailFolder.INBOX,null,memory,
            onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})}}
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:arrive-0").fetchSemanticsNodes().isNotEmpty()}
        rule.runOnIdle{messages=listOf(mail.copy(id="new-1",subject="Новое письмо",date=3_000_000L))+messages}
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:new-1").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("mail-row:new-1").assertIsDisplayed()
        rule.onNodeWithTag("inbox-list").performScrollToIndex(20)
        rule.waitUntil(10000){rule.onAllNodesWithContentDescription("Вернуться к началу списка").fetchSemanticsNodes().isNotEmpty()}
        val oldKey=memory.list.layoutInfo.visibleItemsInfo.first().key
        val oldOffset=memory.list.firstVisibleItemScrollOffset
        rule.runOnIdle{messages=listOf(mail.copy(id="new-2",date=4_000_000L))+messages}
        rule.waitUntil(15000){memory.visibleRows?.firstOrNull()?.id=="new-2"}
        rule.runOnIdle{assertEquals(oldKey,memory.list.layoutInfo.visibleItemsInfo.first().key);assertEquals(oldOffset,memory.list.firstVisibleItemScrollOffset)}
        rule.onNodeWithContentDescription("Вернуться к началу списка").performClick()
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:new-2").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("mail-row:new-2").assertIsDisplayed()
    }

    @Test fun startupRecordingCapturesFramesAndStopsWithAReport() {
        val activity = rule.activity
        val context = object : android.content.ContextWrapper(activity.applicationContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("startup-frame-probe-test", mode)
        }
        val probe = StartupFrameProbe(context)
        rule.runOnUiThread {
            probe.arm()
            probe.start(activity.window, 60f)
        }
        try {
            rule.setContent {
                SideEffect { probe.markInbox(cached = true, ready = true) }
                Text("Проверка кадров запуска")
            }
            rule.onNodeWithText("Проверка кадров запуска").assertIsDisplayed()
            rule.waitUntil(12_000) { probe.report.isNotBlank() }
            assertTrue(probe.report.contains(Regex("Получено кадров: [1-9]")))
            assertTrue(probe.report.contains("Сохранённая страница: +"))
            assertTrue(probe.report.contains("Полное состояние списка: +"))
        } finally {
            rule.runOnUiThread { probe.stop(activity.window) }
        }
    }

    @Test fun compareScrollFrameTimesWithAutomaticPanelsEnabledAndDisabled() {
        val enabled=mutableStateOf(true)
        val messages=(0..99).map{i->mail.copy(id="perf-$i",subject="Письмо $i",date=2_000_000L-i)}
        rule.setContent {val memory=remember{InboxMemory()};MailTheme {InboxScreen(UiState(loaded=true,accounts=listOf(account),messages=messages,autoHidePanels=enabled.value),"a",MailFolder.INBOX,null,memory,
            onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},onDelete={_,_->},onSearch={},onOpen={})}}
        rule.waitUntil(15000){rule.onAllNodesWithTag("mail-row:perf-0").fetchSemanticsNodes().isNotEmpty()}
        for(auto in listOf(true,false)) {
            rule.runOnIdle{enabled.value=auto}
            rule.onNodeWithTag("inbox-list").performScrollToIndex(0)
            rule.waitForIdle()
            val samples=java.util.Collections.synchronizedList(mutableListOf<Long>())
            val listener=android.view.Window.OnFrameMetricsAvailableListener { _,metrics,_->samples.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)) }
            rule.runOnUiThread{rule.activity.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(android.os.Looper.getMainLooper()))}
            repeat(3){rule.onNodeWithTag("inbox-list").performTouchInput{swipe(Offset(center.x,height*0.75f),Offset(center.x,height*0.25f),500)}}
            rule.waitForIdle()
            rule.runOnUiThread{rule.activity.window.removeOnFrameMetricsAvailableListener(listener)}
            val sorted=samples.toList().sorted()
            assertTrue("Expected frame measurements",sorted.isNotEmpty())
            android.util.Log.i("MailScrollPerf","auto=$auto frames=${sorted.size} p90_ms=${sorted[(sorted.size*9/10).coerceAtMost(sorted.lastIndex)]/1_000_000.0} over_16ms=${sorted.count{it>16_666_667}}")
        }
    }

    @Test fun deletingAnOpenLetterRequiresConfirmation() {
        var deletions=0
        rule.setContent {
            MailTheme {
                ReaderScreen(mail,account,false,emptyList(),emptySet(),onBack={},onStar={},
                    onInline={error("unused")},onLink={},onDownload={},onOpenAttachment={},onOpenLocal={},
                    onReply={},onForward={},onDelete={deletions++})
            }
        }
        rule.onNodeWithContentDescription("Удалить письмо").performClick()
        rule.onNodeWithText("Удалить письмо?").assertIsDisplayed()
        rule.onNodeWithText("Отмена").performClick()
        rule.runOnIdle{assertEquals(0,deletions)}
        rule.onNodeWithContentDescription("Удалить письмо").performClick()
        rule.onNodeWithText("Удалить",substring=false).performClick()
        rule.runOnIdle{assertEquals(1,deletions)}
    }

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

    @Test fun startupInboxShowsCachedRowsAndKeepsPositionWhenFullPagingStarts() {
        val memory=InboxMemory()
        val letters=(1..80).map{i->mail.copy(id="startup-$i",date=100L-i)}
        var model by mutableStateOf(UiState(accounts=listOf(account),messages=letters.take(LOCAL_INBOX_PAGE_SIZE),startupInbox=true))
        var reads=0
        rule.setContent {
            val state=model
            MailTheme {
                InboxScreen(state,null,MailFolder.INBOX,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},
                    onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},
                    onDelete={_,_->},onSearch={},onOpen={},readLocalInboxPage={_,after,limit->
                        check(!state.startupInbox)
                        reads++
                        val remaining=letters.drop(if(after==null)0 else letters.indexOfFirst{it.id==after.id}+1)
                        val items=remaining.take(limit)
                        LocalInboxPage(items,if(remaining.size>items.size)items.last().inboxCursor()else null)
                    })
            }
        }
        rule.onNodeWithTag("mail-row:startup-1").assertIsDisplayed()
        rule.onNodeWithTag("inbox-list").performScrollToIndex(12)
        rule.onNodeWithTag("mail-row:startup-13").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0,reads)
            model=model.copy(loaded=true,messages=letters,startupInbox=false)
        }
        rule.waitUntil(10_000){reads>0}
        rule.onNodeWithTag("mail-row:startup-13").assertIsDisplayed()
        rule.runOnIdle{assertEquals("startup-13",memory.list.layoutInfo.visibleItemsInfo.first().key)}
    }

    @Test fun localInboxLoadsMoreNearTheEndAndKeepsTheVisibleRowOnArrival() {
        val memory=InboxMemory()
        val letters=(1..100).map{i->mail.copy(id="page-$i",subject="Письмо $i",date=200L-i)}
        var model by mutableStateOf(UiState(loaded=true,accounts=listOf(account),messages=letters))
        val requests=mutableListOf<Pair<LocalInboxCursor?,Int>>()
        rule.setContent {
            val state=model
            MailTheme {
                InboxScreen(state,"a",MailFolder.INBOX,null,memory,onAccount={},onEnable={_,_->},onFolder={},onServerFolder={},onQueue={},
                    onAdd={},onExit={},onNoticeAnchor={},onSettings={},onDelivery={},onRefresh={},onCompose={},onStar={},onMarkRead={_,_,_->},
                    onDelete={_,_->},onSearch={},onOpen={},readLocalInboxPage={_,cursor,limit->
                        requests+=cursor to limit
                        val start=if(cursor==null)0 else model.messages.indexOfFirst{it.id==cursor.id}+1
                        val items=model.messages.drop(start).take(limit)
                        LocalInboxPage(items,if(start+items.size<model.messages.size)items.last().inboxCursor()else null)
                    })
            }
        }
        rule.waitUntil(10_000){memory.visibleRows?.size==40}
        rule.runOnIdle{assertEquals(listOf(null to 40),requests)}
        rule.onNodeWithTag("inbox-list").performScrollToIndex(35)
        rule.waitUntil(10_000){memory.visibleRows?.size==80}
        val row=rule.onNodeWithTag("mail-row:page-36")
        row.assertIsDisplayed()
        val top=row.fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle{model=model.copy(messages=listOf(mail.copy(id="page-new",date=300))+model.messages)}
        rule.waitUntil(10_000){memory.visibleRows?.firstOrNull()?.id=="page-new"}
        row.assertIsDisplayed()
        assertEquals(top,row.fetchSemanticsNode().boundsInRoot.top,2f)
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
        val list=rule.onNodeWithTag("inbox-list")
        list.performScrollToIndex(20)
        repeat(4){
            if(memory.bottomVisible.value)list.performTouchInput{swipeUp(durationMillis=450)}
        }
        rule.waitUntil(10000){memory.controlsProgress.floatValue==0f && !memory.bottomVisible.value}
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
        rule.onNodeWithContentDescription("Предыдущие сообщения: 2").performClick()
        rule.onNodeWithText("Содержимое первого").assertIsDisplayed()
        rule.onNodeWithText("Содержимое второго").assertIsDisplayed()
        rule.onNodeWithText("Первый",substring=true).assertIsDisplayed()
        rule.onNodeWithText("Второй",substring=true).assertIsDisplayed()
        saveDeviceScreenshot(rule,"expanded-history")
        rule.onNodeWithContentDescription("Предыдущие сообщения: 2").performClick()
        rule.onNodeWithText("Содержимое первого").assertDoesNotExist()
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
