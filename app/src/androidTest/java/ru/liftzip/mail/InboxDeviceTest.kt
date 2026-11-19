@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.liftzip.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
        val compositions=AtomicInteger()
        rule.setContent {
            SideEffect{compositions.incrementAndGet()}
            val state=rememberPullToRefreshState()
            ObservePullRefresh(state,refreshing.value,onRefreshing={},onRest={})
            MaterialTheme {PullToRefreshBox(isRefreshing=refreshing.value,onRefresh={},state=state,
                indicator={PullRefreshHint(state.distanceFraction,refreshing.value,false,false,Modifier.testTag("hint"))}) {
                LazyColumn(Modifier.fillMaxSize()){items(20){Text("Letter $it",Modifier.height(60.dp))}}
            }}
        }
        rule.waitForIdle();rule.onNodeWithTag("hint").assertDoesNotExist()
        rule.mainClock.autoAdvance=false
        rule.runOnIdle{refreshing.value=false}
        repeat(40){rule.mainClock.advanceTimeByFrame();rule.onNodeWithTag("hint").assertDoesNotExist()}
        assertTrue("Automatic refresh must not recompose the screen on every frame: ${compositions.get()}",compositions.get()<=3)
        rule.mainClock.autoAdvance=true
    }

    @Test fun nativeFlingMovesPanelsAndDoesNotRecomposeHeaderEveryFrame() {
        val progress=mutableFloatStateOf(1f);val controller=InboxChromeController()
        val bottomVisible=mutableStateOf(true)
        val headerCompositions=AtomicInteger();val bottomCompositions=AtomicInteger();val listCompositions=AtomicInteger()
        val listState=LazyListState()
        rule.setContent {
            val fraction=remember{ {progress.floatValue} }
            val connection=remember{InboxScrollConnection(controller,progress,bottomVisible,{false},{!listState.canScrollBackward},{false},{},{})}
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={
                SideEffect{headerCompositions.incrementAndGet()}
                Surface{Text("Inbox",Modifier.fillMaxWidth().height(240.dp))}
            },bottomVisible=bottomVisible,bottom={
                SideEffect{bottomCompositions.incrementAndGet()}
                Surface{Text("Navigation",Modifier.fillMaxWidth().height(64.dp))}
            },list={padding->
                SideEffect{listCompositions.incrementAndGet()}
                PullToRefreshBox(isRefreshing=false,onRefresh={},indicator={}) {
                    LazyColumn(Modifier.fillMaxSize().testTag("letters").nestedScroll(connection),state=listState,contentPadding=padding) {
                        items((1..200).toList(),key={it}){Text("Letter $it",Modifier.fillMaxWidth().height(64.dp))}
                    }
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
        val bottomVisible=mutableStateOf(true)
        rule.setContent {
            val fraction=remember{ {progress.floatValue} }
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={Text("Header",Modifier.height(200.dp))},bottomVisible=bottomVisible,bottom={Text("Bottom",Modifier.height(64.dp))},list={padding->
                LazyColumn(Modifier.fillMaxSize().testTag("letters"),contentPadding=padding){items(30){Text("Letter $it",Modifier.height(64.dp))}}
            })}
        }
        rule.mainClock.advanceTimeBy(1000);rule.runOnIdle{assertEquals(0.5f,progress.floatValue,0f)}
        rule.runOnIdle{progress.floatValue=0f;bottomVisible.value=false};rule.waitForIdle()
        rule.onNodeWithText("Header").assertIsNotDisplayed()
        rule.onNodeWithText("Bottom").assertIsNotDisplayed()
        rule.onNodeWithTag("letters").assertIsDisplayed()
    }

    @Test fun bottomControlsAreOpaqueOrAbsentEvenWhenHeaderIsPartiallyHidden() {
        val progress=mutableFloatStateOf(0.5f)
        val bottomVisible=mutableStateOf(true)
        var density=1f
        rule.setContent {
            density=LocalDensity.current.density
            val fraction=remember{ {progress.floatValue} }
            MaterialTheme(colorScheme=lightColorScheme(surface=Color.White)) {
                InboxViewport(fraction,{},Modifier.fillMaxSize().background(Color.White),header={
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.Blue))
                },bottomVisible=bottomVisible,bottom={Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Green))},list={padding->
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=padding) {
                        item(key="gap"){Box(Modifier.fillMaxWidth().height(100.dp))}
                        item(key="mail"){Box(Modifier.fillMaxWidth().height(2000.dp).background(Color.Red))}
                    }
                })
            }
        }
        rule.waitForIdle()
        val pixels=rule.onRoot().captureToImage().toPixelMap()
        val gap=pixels[pixels.width/2,(90*density).toInt()]
        assertEquals(1f,gap.red,0.04f);assertEquals(1f,gap.green,0.04f);assertEquals(1f,gap.blue,0.04f)
        val bottom=pixels[pixels.width/2,pixels.height-(40*density).toInt()]
        assertEquals(0f,bottom.red,0.04f);assertEquals(1f,bottom.green,0.04f);assertEquals(0f,bottom.blue,0.04f)
        saveDeviceScreenshot(rule,"partial-panels")
        rule.runOnIdle{bottomVisible.value=false};rule.waitForIdle()
        val hidden=rule.onRoot().captureToImage().toPixelMap()
        val uncovered=hidden[hidden.width/2,hidden.height-(40*density).toInt()]
        assertEquals(1f,uncovered.red,0.04f);assertEquals(0f,uncovered.green,0.04f);assertEquals(0f,uncovered.blue,0.04f)
        saveDeviceScreenshot(rule,"hidden-panels")
    }

    @Test fun bottomControlsWaitForActualListTravelInBothDirections() {
        val progress=mutableFloatStateOf(0f)
        val bottomVisible=mutableStateOf(true)
        val controller=InboxChromeController(0f)
        val listState=LazyListState(10)
        var dragPixels=0f
        rule.setContent {
            dragPixels=with(LocalDensity.current){100.dp.toPx()}
            val fraction=remember{{progress.floatValue}}
            val connection=remember{
                InboxScrollConnection(controller,progress,bottomVisible,{false},
                    {!listState.canScrollBackward},{false},{},{},listState)
            }
            MaterialTheme {
                InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={},
                    bottom={Text("Tabs",Modifier.height(80.dp))},bottomVisible=bottomVisible,list={padding->
                        LazyColumn(Modifier.fillMaxSize().testTag("letters").nestedScroll(connection),state=listState,contentPadding=padding) {
                            items(100,key={it}){Text("Card $it",Modifier.fillMaxWidth().height(120.dp))}
                        }
                    })
            }
        }
        val list=rule.onNodeWithTag("letters")
        val tabs=rule.onNodeWithText("Tabs")
        fun drag(direction: Int) {
            list.performTouchInput{down(center);moveBy(Offset(0f,direction*dragPixels));advanceEventTime(500);up()}
        }
        repeat(2){drag(-1)}
        tabs.assertIsDisplayed()
        drag(-1)
        tabs.assertIsNotDisplayed()
        repeat(2){drag(1)}
        tabs.assertIsNotDisplayed()
        drag(1)
        tabs.assertIsDisplayed()
        rule.runOnIdle{assertTrue(listState.firstVisibleItemIndex>0)}
    }

    @Test fun filteringALongListToOneMessageRestoresPanelsAndKeepsThemVisibleOnDrag() {
        val progress=mutableFloatStateOf(0.4f);val controller=InboxChromeController(0.4f)
        val bottomVisible=mutableStateOf(false)
        val list=LazyListState();val count=mutableIntStateOf(200)
        val metrics=mutableStateOf<InboxPanelMetrics?>(null);val scrollable=mutableStateOf<Boolean?>(null)
        rule.setContent {
            val fraction=remember{ {progress.floatValue} }
            val keys=remember(count.intValue){(1..count.intValue).toSet<Any>()}
            ObserveInboxScrollability(list,keys,count.intValue,metrics){long->scrollable.value=long;if(!long){controller.show();progress.floatValue=1f;bottomVisible.value=true}}
            val connection=remember{InboxScrollConnection(controller,progress,bottomVisible,{scrollable.value==false},{!list.canScrollBackward},{false},{},{})}
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),header={Text("Header",Modifier.fillMaxWidth().height(160.dp))},
                bottomVisible=bottomVisible,bottom={Text("Navigation",Modifier.fillMaxWidth().height(80.dp))},onPanelsMeasured={metrics.value=it},list={padding->
                    LazyColumn(Modifier.fillMaxSize().testTag("filtered-letters").nestedScroll(connection),state=list,contentPadding=padding) {
                        items(count.intValue,key={it+1}){Text("Letter ${it+1}",Modifier.fillMaxWidth().height(100.dp))}
                    }
                })}
        }
        rule.waitForIdle();rule.runOnIdle{assertEquals(true,scrollable.value);assertEquals(0.4f,progress.floatValue,0f);count.intValue=1}
        rule.waitForIdle();rule.runOnIdle{assertEquals(false,scrollable.value);assertEquals(1f,progress.floatValue,0f)}
        rule.onNodeWithTag("filtered-letters").performTouchInput{swipeUp()}
        rule.waitForIdle();rule.runOnIdle{assertEquals(1f,progress.floatValue,0f)}
        rule.onNodeWithText("Header").assertIsDisplayed();rule.onNodeWithText("Navigation").assertIsDisplayed()
    }
    @Test fun reversingAHiddenListFinishesTheWholeCardBeforeRevealingPanels() {
        val progress=mutableFloatStateOf(0f);val controller=InboxChromeController(0f)
        val bottomVisible=mutableStateOf(false)
        val list=LazyListState(10,100)
        rule.setContent {
            val fraction=remember{{progress.floatValue}}
            val connection=remember{InboxScrollConnection(controller,progress,bottomVisible,{false},{!list.canScrollBackward},{false},{},{},list)}
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),
                header={Text("Header",Modifier.fillMaxWidth().height(180.dp))},bottomVisible=bottomVisible,bottom={Text("Tabs",Modifier.height(80.dp))},list={padding->
                    LazyColumn(Modifier.fillMaxSize().testTag("boundary-list").nestedScroll(connection),state=list,contentPadding=padding) {
                        items(100,key={it}){Text("Card $it",Modifier.fillMaxWidth().height(120.dp))}
                    }
                })}
        }
        rule.waitForIdle()
        rule.onNodeWithTag("boundary-list").performTouchInput{down(center);moveBy(Offset(0f,60f));advanceEventTime(500);up()}
        rule.waitForIdle()
        rule.runOnIdle{assertEquals(0f,progress.floatValue,0f);assertFalse(bottomVisible.value);assertTrue(list.firstVisibleItemScrollOffset in 1..99)}
        rule.onNodeWithTag("boundary-list").performTouchInput{down(center);moveBy(Offset(0f,160f));advanceEventTime(500);up()}
        rule.waitForIdle()
        rule.runOnIdle{assertTrue(progress.floatValue>0f);assertEquals(10,list.firstVisibleItemIndex);assertEquals(0,list.firstVisibleItemScrollOffset)}
        saveDeviceScreenshot(rule,"row-boundary")
    }
    @Test fun hidingPanelsFirstMovesAClippedCardToTheNextBoundary() {
        val progress=mutableFloatStateOf(1f);val controller=InboxChromeController(1f)
        val bottomVisible=mutableStateOf(true)
        val list=LazyListState(10,100)
        rule.setContent {
            val fraction=remember{{progress.floatValue}}
            val connection=remember{InboxScrollConnection(controller,progress,bottomVisible,{false},{!list.canScrollBackward},{false},{},{},list)}
            MaterialTheme {InboxViewport(fraction,controller::measureHeader,Modifier.fillMaxSize(),
                header={Text("Header",Modifier.fillMaxWidth().height(180.dp))},bottomVisible=bottomVisible,bottom={Text("Tabs",Modifier.height(80.dp))},list={padding->
                    LazyColumn(Modifier.fillMaxSize().testTag("hide-boundary-list").nestedScroll(connection),state=list,contentPadding=padding) {
                        items(100,key={it}){Text("Card $it",Modifier.fillMaxWidth().height(120.dp))}
                    }
                })}
        }
        rule.waitForIdle()
        var remaining=0
        rule.runOnIdle{val info=list.layoutInfo.visibleItemsInfo.first{it.index==list.firstVisibleItemIndex};remaining=info.size-list.firstVisibleItemScrollOffset;assertTrue(remaining>100)}
        rule.onNodeWithTag("hide-boundary-list").performTouchInput{down(center);moveBy(Offset(0f,-60f));advanceEventTime(500);up()}
        rule.waitForIdle();rule.runOnIdle{assertEquals(1f,progress.floatValue,0f);assertTrue(bottomVisible.value);assertTrue(list.firstVisibleItemScrollOffset>100)}
        rule.onNodeWithTag("hide-boundary-list").performTouchInput{down(center);moveBy(Offset(0f,-(remaining+160).toFloat()));advanceEventTime(500);up()}
        rule.waitForIdle();rule.runOnIdle{assertTrue(progress.floatValue<1f);assertEquals(11,list.firstVisibleItemIndex);assertEquals(0,list.firstVisibleItemScrollOffset)}
        saveDeviceScreenshot(rule,"hide-row-boundary")
    }
}
