package ru.liftzip.mail

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderOpeningDeviceTest {
    @get:Rule val rule=createComposeRule()
    private fun analysis(body: String)=ConversationText.parse(body).let{it to HtmlMail.analyzeHistory("",it)}
    private fun message(id: String,body: String)=MailItem(id,"a",sender="Отправитель",senderEmail="sender@example.com",
        recipients="test@example.com",subject="Проверка открытия",body=body,date=1,contentVersion=MailContent.FORMAT_VERSION)

    @Test fun one250msBudgetCoversAllOpeningStagesWithoutDelayingContent() {
        val stage=mutableIntStateOf(0)
        rule.mainClock.autoAdvance=false
        rule.setContent {
            // Storage, analysis and native commit share the same pending operation.
            if(rememberReaderProgress("letter",stage.intValue<3))Text("progress",Modifier.testTag("progress"))
            Text("stage ${stage.intValue}")
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle{stage.intValue=1};rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle{stage.intValue=2};rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("progress").assertExists()
        rule.runOnIdle{stage.intValue=3};rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("stage 3").assertExists()
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.mainClock.autoAdvance=true
    }
    @Test fun quickCompletionAndSwitchingLettersCancelThePendingIndicator() {
        val loading=mutableStateOf(true);val request=mutableStateOf("first")
        rule.mainClock.autoAdvance=false
        rule.setContent {if(rememberReaderProgress(request.value,loading.value))Text("progress",Modifier.testTag("progress"))}
        rule.mainClock.advanceTimeByFrame();rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle{loading.value=false};rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(512)
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.runOnIdle{loading.value=true;request.value="second"};rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle{request.value="third"};rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(192)
        // The second request would have expired, but the third still has its own budget.
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag("progress").assertExists()
        rule.runOnIdle{loading.value=false};rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.mainClock.autoAdvance=true
    }
    @Test fun updatingAndTransientPreviewsRetainTheLastCompleteContentAndCancelOldWork() {
        ReaderDocuments.analysis.clear()
        val first=message("opening-state","Первое письмо")
        val current=mutableStateOf(first)
        val bodies=listOf(first.body,"Обновлённое письмо","Отменённая подготовка","Другое письмо")
        val gates=bodies.associateWith{CompletableDeferred<Pair<QuotedConversation,HtmlMail.HistoryAnalysis>>()}
        val started=ConcurrentHashMap.newKeySet<String>()
        val presented=AtomicReference<PreparedReaderContent?>()
        rule.setContent {
            val content by rememberReaderContent(current.value){key->started+=key.body;gates.getValue(key.body).await()}
            SideEffect{presented.set(content)}
            Text(content?.conversation?.original?:"Ожидание")
        }
        rule.waitUntil(10_000){first.body in started}
        rule.onNodeWithText("Ожидание").assertExists()
        gates.getValue(first.body).complete(analysis(first.body))
        rule.waitUntil(10_000){presented.get()?.message?.body==first.body}
        rule.runOnIdle{current.value=first.copy(body=bodies[1])}
        rule.waitUntil(10_000){bodies[1] in started}
        rule.onNodeWithText(first.body).assertExists()
        rule.onNodeWithText("Ожидание").assertDoesNotExist()
        gates.getValue(bodies[1]).complete(analysis(bodies[1]))
        rule.waitUntil(10_000){presented.get()?.message?.body==bodies[1]}
        rule.runOnIdle{current.value=first.copy(body="Только предпросмотр",previewOnly=true)}
        rule.waitForIdle()
        rule.onNodeWithText(bodies[1]).assertExists()
        assertFalse("A preview must never be parsed as a full body","Только предпросмотр" in started)
        rule.runOnIdle{current.value=first.copy(body=bodies[2])}
        rule.waitUntil(10_000){bodies[2] in started}
        rule.onNodeWithText(bodies[1]).assertExists()
        rule.runOnIdle{current.value=message("opening-other",bodies[3])}
        rule.waitUntil(10_000){bodies[3] in started}
        rule.onNodeWithText(bodies[1]).assertDoesNotExist()
        rule.onNodeWithText("Ожидание").assertExists()
        gates.getValue(bodies[2]).complete(analysis(bodies[2]))
        rule.waitForIdle()
        rule.onNodeWithText(bodies[2]).assertDoesNotExist()
        gates.getValue(bodies[3]).complete(analysis(bodies[3]))
        rule.waitUntil(10_000){presented.get()?.message?.id=="opening-other"}
        rule.onNodeWithText(bodies[3]).assertExists()
        assertEquals(presented.get()!!.message.body,presented.get()!!.conversation.original)
    }
    @Test fun cachedAnalysisIsVisibleBeforeBackgroundPreparationCompletes() {
        val source=message("opening-cached","Сохранённое письмо").copy(uid=42,validity=7,contentVersion=0)
        val key=ReaderAnalysisCache.Key("a:${source.id}:${source.validity}",source.body,source.htmlBody,source.contentVersion)
        ReaderDocuments.analysis.prepare(key)
        val blocked=CompletableDeferred<Pair<QuotedConversation,HtmlMail.HistoryAnalysis>>()
        rule.setContent {
            val content by rememberReaderContent(source){blocked.await()}
            Text(content?.conversation?.original?:"Ожидание")
        }
        rule.onNodeWithText(source.body).assertExists()
        rule.onNodeWithText("Ожидание").assertDoesNotExist()
        assertFalse(blocked.isCompleted)
    }
}
