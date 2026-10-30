@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxDeviceTest {
    @get:Rule val rule=createComposeRule()

    @Test fun automaticRefreshReturnAnimationHasNoGestureCircle() {
        val refreshing=mutableStateOf(true)
        rule.setContent {
            val state=rememberPullToRefreshState()
            MaterialTheme {PullToRefreshBox(isRefreshing=refreshing.value,onRefresh={},state=state,
                indicator={PullRefreshHint(state.distanceFraction,refreshing.value,false,false,Modifier.testTag("hint"))}) {
                LazyColumn(Modifier.fillMaxSize()){items(20){Text("Letter $it",Modifier.height(60.dp))}}
            }}
        }
        rule.waitForIdle();rule.onNodeWithTag("hint").assertDoesNotExist()
        rule.mainClock.autoAdvance=false
        rule.runOnIdle{refreshing.value=false}
        repeat(40){rule.mainClock.advanceTimeByFrame();rule.onNodeWithTag("hint").assertDoesNotExist()}
        rule.mainClock.autoAdvance=true
    }

    @Test fun nativeFlingMovesPanelsAndDoesNotRecomposeHeaderEveryFrame() {
        val progress=mutableFloatStateOf(1f);val controller=InboxChromeController()
        val headerCompositions=AtomicInteger();val bottomCompositions=AtomicInteger();val listCompositions=AtomicInteger()
        val listState=LazyListState()
        rule.setContent {
            val fraction=remember{ {progress.floatValue} }
            val connection=remember{InboxScrollConnection(controller,progress,{false},{!listState.canScrollBackward},{false},{},{})}
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={
                SideEffect{headerCompositions.incrementAndGet()}
                Surface{Text("Inbox",Modifier.fillMaxWidth().height(240.dp))}
            },bottom={
                SideEffect{bottomCompositions.incrementAndGet()}
                Surface{Text("Navigation",Modifier.fillMaxWidth().height(64.dp))}
            },list={padding->
                SideEffect{listCompositions.incrementAndGet()}
                LazyColumn(Modifier.fillMaxSize().testTag("letters").nestedScroll(connection),state=listState,contentPadding=padding) {
                    items((1..200).toList(),key={it}){Text("Letter $it",Modifier.fillMaxWidth().height(64.dp))}
                }
            })}
        }
        rule.waitForIdle();rule.mainClock.autoAdvance=false
        rule.onNodeWithTag("letters").performTouchInput{swipe(center,center+Offset(0f,-60f),64)}
        rule.mainClock.advanceTimeBy(32)
        var first=1f;rule.runOnIdle{first=progress.floatValue}
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle{assertTrue("Fling must keep changing visibility: $first -> ${progress.floatValue}",progress.floatValue<first)}
        repeat(120){rule.mainClock.advanceTimeByFrame()}
        assertTrue("Header recomposed ${headerCompositions.get()} times",headerCompositions.get()<=2)
        assertTrue("Bottom recomposed ${bottomCompositions.get()} times",bottomCompositions.get()<=2)
        assertTrue("List slot recomposed ${listCompositions.get()} times",listCompositions.get()<=2)
        rule.mainClock.autoAdvance=true
    }

    @Test fun pausedPartialProgressStaysFixedAndInvisiblePanelsDoNotBlockLetters() {
        val progress=mutableFloatStateOf(0.5f);val controller=InboxChromeController(0.5f)
        rule.setContent {
            val fraction=remember{ {progress.floatValue} }
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={Text("Header",Modifier.height(200.dp))},bottom={Text("Bottom",Modifier.height(64.dp))},list={padding->
                LazyColumn(Modifier.fillMaxSize().testTag("letters"),contentPadding=padding){items(30){Text("Letter $it",Modifier.height(64.dp))}}
            })}
        }
        rule.mainClock.advanceTimeBy(1000);rule.runOnIdle{assertEquals(0.5f,progress.floatValue,0f)}
        rule.runOnIdle{progress.floatValue=0f};rule.waitForIdle()
        rule.onNodeWithText("Header").assertIsNotDisplayed()
        rule.onNodeWithText("Bottom").assertIsNotDisplayed()
        rule.onNodeWithTag("letters").assertIsDisplayed()
    }
}
