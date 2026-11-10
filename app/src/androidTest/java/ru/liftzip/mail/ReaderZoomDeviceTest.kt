package ru.liftzip.mail

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** During-gesture geometry checks. Final-position assertions alone cannot detect pinch jitter. */
@RunWith(AndroidJUnit4::class)
class ReaderZoomDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val message=MailItem("reader-zoom-regression","a",sender="Sender",senderEmail="s@example.com",recipients="r@example.com",subject="Длинное письмо",body="Ответ\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Предыдущий текст",date=1,
        htmlBody="<p>Ответ</p><div style='height:100000px'></div><div class='gmail_quote'><p>Предыдущий текст</p></div>",contentVersion=MailContent.FORMAT_VERSION)

    private data class Sample(val time: Long,val scale: Float,val progress: Float,val anchorY: Float,val left: Float,val top: Float,val width: Int,val height: Int,val scrollY: Int)

    private inner class FrameSampler(private val view: MailHtmlWebView,private val focusY: Float) {
        private val samples=mutableListOf<Sample>()
        private var active=false
        private var base=0f
        private var anchorCss=0f
        private val callback=object: Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if(!active)return
                val scale=view.scale
                if(scale>0f) {
                    val documentTop=topInRoot(view)
                    val screen=IntArray(2);view.getLocationOnScreen(screen)
                    val anchor=documentTop+anchorCss*scale-view.scrollY
                    val progress=((scale/base-1.03f)/0.47f).coerceIn(0f,1f)
                    samples+=Sample(frameTimeNanos/1_000_000,scale,progress,anchor,screen[0].toFloat(),screen[1].toFloat(),view.width,view.height,view.scrollY)
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun start()=rule.runOnUiThread {
            base=view.scale
            anchorCss=(focusY-topInRoot(view)+view.scrollY)/base
            samples.clear();active=true
            Choreographer.getInstance().postFrameCallback(callback)
        }
        fun stop(): List<Sample> {
            lateinit var result: List<Sample>
            rule.runOnUiThread{active=false;result=samples.toList()}
            return result
        }
    }

    private fun topInRoot(view: View): Float {
        val own=IntArray(2);val root=IntArray(2)
        view.getLocationInWindow(own);rule.activity.window.decorView.getLocationInWindow(root)
        return (own[1]-root[1]).toFloat()
    }
    private fun webView(root: View=rule.activity.window.decorView): MailHtmlWebView? {
        if(root is MailHtmlWebView)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)webView(root.getChildAt(i))?.let{return it}
        return null
    }
    @Composable private fun reader(m: MailItem=message) {
        ReaderScreen(m,null,false,emptyList(),emptySet(),{},{},{error("No inline images")},{},{},{},{},{},{})
    }
    private fun loaded(): MailHtmlWebView {
        rule.waitForIdle()
        try {
            rule.waitUntil(60_000){var ready=false;rule.runOnUiThread{ready=webView()?.let{it.interactionEnabled && it.contentHeight>0 && it.height>20}?:false};ready}
        }catch(error: Throwable) {
            rule.onRoot().printToLog("MailReaderTree")
            Thread.getAllStackTraces().filterKeys{it.name.contains("DefaultDispatcher")}.forEach{(thread,stack)->
                android.util.Log.i("MailReaderReady","worker ${thread.name}: "+stack.joinToString(" | "))
            }
            rule.runOnUiThread{val v=webView();android.util.Log.i("MailReaderReady","load timeout view=$v enabled=${v?.interactionEnabled} h=${v?.height} content=${v?.contentHeight}")}
            throw error
        }
        var view: MailHtmlWebView?=null;rule.runOnIdle{view=webView()};return view!!
    }
    private fun center(): Offset {
        var result=Offset.Zero
        rule.runOnUiThread {
            val rect=Rect();assertTrue(webView()!!.getGlobalVisibleRect(rect))
            val root=IntArray(2);rule.activity.window.decorView.getLocationOnScreen(root)
            result=Offset(rect.exactCenterX()-root[0],rect.exactCenterY()-root[1])
        }
        return result
    }

    // Pace real Android dispatches in wall-clock time. Compose's queued test gestures
    // and a sleep inside performTouchInput cannot represent a stationary real-time hold.
    private inner class Pinch(private val focus: Offset,private var half: Float) {
        private val downTime=SystemClock.uptimeMillis()
        private fun event(action: Int,count: Int) {
            val props=Array(count){index->MotionEvent.PointerProperties().apply{id=index;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(count){index->MotionEvent.PointerCoords().apply{x=focus.x+if(index==0)-half else half;y=focus.y;pressure=1f;size=1f}}
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try {rule.runOnUiThread{rule.activity.window.decorView.dispatchTouchEvent(event)}}finally{event.recycle()}
            SystemClock.sleep(20)
        }
        fun down(){event(MotionEvent.ACTION_DOWN,1);event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2)}
        fun move(next: Float){half=next;event(MotionEvent.ACTION_MOVE,2)}
        fun up(){event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2);event(MotionEvent.ACTION_UP,1)}
    }
    private fun continuousOutAndBack(center: Offset,halfStart: Float,halfEnd: Float,steps: Int=24) {
        val pinch=Pinch(center,halfStart);pinch.down()
        for(step in 1..steps)pinch.move(halfStart+(halfEnd-halfStart)*step/steps)
        for(step in 1..steps)pinch.move(halfEnd+(halfStart-halfEnd)*step/steps)
        pinch.up()
    }

    private fun drag(start: Offset,delta: Offset) {
        val downTime=SystemClock.uptimeMillis()
        fun send(action: Int,point: Offset) {
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,point.x,point.y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try{rule.runOnUiThread{rule.activity.window.decorView.dispatchTouchEvent(event)}}finally{event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,start)
        for(step in 1..24){SystemClock.sleep(20);send(MotionEvent.ACTION_MOVE,start+delta*(step/24f))}
        // Release at rest so a fling cannot mask the direction/viewport assertions.
        SystemClock.sleep(200);send(MotionEvent.ACTION_MOVE,start+delta);send(MotionEvent.ACTION_UP,start+delta)
        rule.waitForIdle()
    }

    @Test fun scrollControlsToolbarAtBaseScaleAndZoomTakesPriorityAfterPinch() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        val density=view.resources.displayMetrics.density
        val nativeHeight=view.height
        repeat(2){pass->
            if(pass==1) {
                val focus=center();val sampler=FrameSampler(view,focus.y);sampler.start()
                val pinch=Pinch(focus,48f*density);pinch.down()
                for(step in 1..20)pinch.move((48f+48f*step/20)*density)
                pinch.up();SystemClock.sleep(250)
                val samples=sampler.stop();rule.waitForIdle()
                assertTrue("Native pinch did not enlarge HTML",samples.any{it.progress>0.1f})
                assertStableViewport(samples)
            }
            if(pass==0)rule.onNodeWithContentDescription("Назад").assertIsDisplayed()
            else rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
            drag(center(),Offset(0f,-240f*density))
            rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
            rule.onNodeWithText("Длинное письмо").assertIsNotDisplayed()
            rule.runOnIdle{assertEquals("Hiding chrome must not resize WebView",nativeHeight,view.height)}
            drag(center(),Offset(0f,120f*density))
            if(pass==0)rule.onNodeWithContentDescription("Назад").assertIsDisplayed()
            else rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
            rule.runOnIdle{assertEquals("Revealing chrome must not resize WebView",nativeHeight,view.height);assertSame(view,webView())}
        }
    }

    private fun screenshot(name: String){saveDeviceScreenshot(rule,name)}
    @Test fun nativePinchHidesMenuSubjectAndImagesThenResetRestoresThem() {
        val rows=(1..80).joinToString(""){index->"<tr><td style='padding:16px;background:#abc9ed'>Row $index</td><td style='padding:16px'>Table content $index</td></tr>"}
        val withImages=message.copy(htmlBody="<p>Ответ</p><img src='https://example.com/reader-test.png'><table style='width:1200px'>$rows</table><div class='gmail_quote'><p>Предыдущий текст</p></div>")
        rule.setContent{MailTheme{reader(withImages)}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.onNodeWithContentDescription("Назад").assertIsDisplayed()
        rule.onNodeWithText("Длинное письмо").assertIsDisplayed()
        rule.onNodeWithText("Показать изображения").assertIsDisplayed()
        screenshot("reader-initial")
        val focus=center();val density=view.resources.displayMetrics.density
        val sampler=FrameSampler(view,focus.y);sampler.start()
        val pinch=Pinch(focus,48f*density);pinch.down()
        for(step in 1..24)pinch.move((48f+80f*step/24)*density)
        pinch.up();SystemClock.sleep(300)
        val samples=sampler.stop();rule.waitForIdle()
        assertStableViewport(samples)
        assertTrue("Pinch must reach the fully hidden chrome state",samples.any{it.progress>=0.999f})
        rule.onNodeWithContentDescription("Назад").assertIsNotDisplayed()
        rule.onNodeWithText("Длинное письмо").assertIsNotDisplayed()
        rule.onNodeWithText("Показать изображения").assertIsNotDisplayed()
        screenshot("reader-zoom-hidden")
        rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
        rule.waitUntil(10_000){var initial=false;rule.runOnUiThread{initial=view.atInitialScale()};initial}
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Назад").assertIsDisplayed()
        rule.onNodeWithText("Длинное письмо").assertIsDisplayed()
        rule.onNodeWithText("Показать изображения").assertIsDisplayed()
        screenshot("reader-reset")
        rule.runOnIdle{assertSame(view,webView())}
    }

    @Test fun fittedWideMailDoesNotOverlapChromeAtPartialZoom() {
        val m=message.copy(subject="Subject that occupies two lines above the sender and images",
            htmlBody="<div style='height:40px;background:#00ff00'></div><table width='720'><tr><td>Letter</td></tr></table><p style='white-space:nowrap'>"+"UNBREAKABLE".repeat(25)+"</p><img src='https://reader-fixture.invalid/image.png'>")
        rule.setContent{MailTheme{reader(m)}}
        val view=loaded()
        fun assertNoOverlap() {
            val header=rule.onNodeWithTag("reader-message-header").fetchSemanticsNode().boundsInRoot
            val toolbar=rule.onNodeWithTag("reader-toolbar").fetchSemanticsNode().boundsInRoot
            val subject=rule.onNodeWithText(m.subject).fetchSemanticsNode().boundsInRoot
            assertTrue("Toolbar covers subject: $toolbar / $subject",toolbar.bottom<=subject.top+2f)
            var firstGreen=-1
            rule.runOnUiThread {
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val x=view.width/2
                    firstGreen=(0 until bitmap.height).firstOrNull{y->
                        val pixel=bitmap.getPixel(x,y)
                        Color.green(pixel)>200 && Color.red(pixel)<50 && Color.blue(pixel)<50
                    }?:-1
                }finally{bitmap.recycle()}
            }
            assertTrue("Native content marker was not rendered",firstGreen>=0)
            assertTrue("Header covers first HTML row: header=$header nativeTop=$firstGreen",topInRoot(view)+firstGreen>=header.bottom-3f)
        }
        assertNoOverlap()
        val focus=center();val density=view.resources.displayMetrics.density
        val sampler=FrameSampler(view,focus.y);sampler.start()
        val pinch=Pinch(focus,48f*density);pinch.down()
        try {
            for(step in 1..8)pinch.move((48f+10f*step/8)*density)
            rule.waitForIdle()
            assertNoOverlap()
            screenshot("reader-partial-no-overlap")
        }finally{pinch.up()}
        assertStableViewport(sampler.stop())
        rule.onNodeWithContentDescription("Вернуть исходный масштаб").performClick()
        rule.waitUntil(10_000){var initial=false;rule.runOnUiThread{initial=view.atInitialScale()};initial}
        rule.waitForIdle()
        assertNoOverlap()
    }

    private fun assertStableViewport(samples: List<Sample>) {
        val heights=samples.map{it.height}.distinct()
        val heightChanges=samples.zipWithNext().count{it.first.height!=it.second.height}
        assertTrue("Native height must remain constant for the entire pinch; heights=$heights, changes=$heightChanges, samples=$samples",heights.size==1 && heightChanges==0)
        val widths=samples.map{it.width}.distinct()
        assertTrue("Native width must remain constant for the entire pinch; widths=$widths",widths.size==1)
        val positions=samples.map{it.left to it.top}.distinct()
        assertTrue("WebView screen position must remain constant for the entire pinch; positions=$positions, samples=$samples",positions.size==1)
    }

    private fun assertAnchorContinuous(samples: List<Sample>,density: Float) {
        val transition=samples.filter{it.progress>0.001f}
        assertTrue("The pinch must cross the collapse threshold; got ${samples.size} frames",transition.size>=4)
        assertTrue("The pinch must reach the fully collapsed state",samples.any{it.progress>=0.999f})
        var reversed=false
        for(index in 1 until transition.size)if(transition[index].scale<transition[index-1].scale*0.999f)reversed=true
        assertTrue("The same gesture must exercise both scale-out and scale-back",reversed)
        // Include the entry/exit threshold frames: excluding them would miss a one-off jump.
        // The sampled document point was under the stationary focus before pinch. With no
        // vertical finger motion it must remain there. Allow only pixel rounding and four dp
        // per frame; do not hide a visible discontinuity behind a device-specific large value.
        val tolerance=4f*density+2f
        val worst=samples.zipWithNext().maxOfOrNull{abs(it.second.anchorY-it.first.anchorY)}?:0f
        assertTrue("Document anchor jumped by $worst px (limit $tolerance); samples=$samples",worst<=tolerance)
        assertStableViewport(samples)
    }

    @Test fun continuousPinchOutAndBackKeepsDocumentAnchorContinuous() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.runOnIdle{view.scrollContent(10_000)}
        val focus=center();val density=view.resources.displayMetrics.density
        val sampler=FrameSampler(view,focus.y);sampler.start()
        continuousOutAndBack(focus,48f*density,160f*density)
        // Keep sampling after UP so a deferred chrome/layout update cannot escape the check.
        Thread.sleep(250)
        val samples=sampler.stop()
        rule.waitForIdle()
        assertAnchorContinuous(samples,density)
        rule.runOnIdle{assertSame(view,webView())}
        rule.onNodeWithContentDescription("Предыдущие сообщения: 1").assertExists()
        rule.onNodeWithContentDescription("Ответить или переслать").assertIsDisplayed()
    }

    @Test fun pinchFromPartiallyHiddenToolbarKeepsScreenGeometryStable() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.runOnIdle{view.scrollPage?.invoke(20f*view.resources.displayMetrics.density)}
        rule.waitForIdle()
        val focus=center()
        val sampler=FrameSampler(view,focus.y);sampler.start()
        continuousOutAndBack(focus,48f*view.resources.displayMetrics.density,160f*view.resources.displayMetrics.density)
        Thread.sleep(250)
        val samples=sampler.stop()
        assertTrue("No frames sampled",samples.size>4)
        assertStableViewport(samples)
    }

    @Test fun stoppingAtIntermediateZoomHasNoDeferredGeometryMotion() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.runOnIdle{view.scrollContent(10_000)}
        val focus=center();val density=view.resources.displayMetrics.density
        val sampler=FrameSampler(view,focus.y);sampler.start()
        val pinch=Pinch(focus,48f*density);pinch.down()
        for(step in 1..16)pinch.move((48f+22f*step/16)*density)
        SystemClock.sleep(400)
        pinch.move(70f*density)
        pinch.up()
        val samples=sampler.stop();rule.waitForIdle()
        assertTrue("The fixture must stop inside the transition",samples.any{it.progress>0.1f && it.progress<0.9f})
        val lastScaleChange=samples.zipWithNext().filter{abs(it.first.scale-it.second.scale)>0.00001f}.maxOfOrNull{it.second.time}?:0L
        val held=samples.filter{it.time>=lastScaleChange+150 && it.progress>0.1f && it.progress<0.9f}
        assertTrue("No frames were sampled during the stationary hold",held.size>=3)
        fun range(selector:(Sample)->Float)=held.maxOf(selector)-held.minOf(selector)
        assertEquals("Panel/body top moved after scale stopped",0f,range{it.top},view.resources.displayMetrics.density+1f)
        assertEquals("Panel/body left moved after scale stopped",0f,range{it.left},1f)
        assertEquals("WebView width changed after scale stopped",0f,range{it.width.toFloat()},1f)
        assertEquals("WebView height changed after scale stopped",0f,range{it.height.toFloat()},1f)
        assertEquals("Native scroll changed after scale stopped",0f,range{it.scrollY.toFloat()},1f)
        assertEquals("Document anchor moved after scale stopped",0f,range{it.anchorY},view.resources.displayMetrics.density+1f)
        rule.runOnIdle{assertSame(view,webView())}
    }


    @Test fun imageToggleAtPartialZoomPreservesTheReadingPosition() {
        val withImages=message.copy(htmlBody="<p>Ответ</p><img src='https://reader-fixture.invalid/image.png'><div style='height:100000px'></div>")
        rule.setContent{MailTheme{reader(withImages)}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        rule.runOnIdle{view.zoomBy(1.12f)}
        rule.waitForIdle()
        for(label in listOf("Показать изображения","Скрыть внешние изображения")) {
            var scale=1f;var y=0;var revision=0L
            rule.runOnIdle{scale=view.relativeScale();y=view.scrollY;revision=view.documentGeneration()}
            rule.onNodeWithText(label).performClick()
            rule.waitUntil(15_000){
                var ready=false
                rule.runOnUiThread{ready=view.documentGeneration()>revision && view.interactionEnabled}
                ready
            }
            rule.runOnIdle{
                assertSame(view,webView())
                assertEquals("Image toggle reset zoom",scale,view.relativeScale(),0.03f)
                assertEquals("Image toggle moved the reading position",y.toFloat(),view.scrollY.toFloat(),3f)
            }
        }
    }

    @Test fun tenRealPinchCyclesDoNotAccumulateHistoryOffset() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        val history=rule.onNodeWithContentDescription("Предыдущие сообщения: 1")
        val original=history.fetchSemanticsNode().boundsInRoot.top
        val density=view.resources.displayMetrics.density
        repeat(10) {cycle->
            val focus=center()
            continuousOutAndBack(focus,48f*density,150f*density,steps=10)
            rule.waitForIdle()
            assertEquals("History drifted after real pinch cycle $cycle",original,history.fetchSemanticsNode().boundsInRoot.top,8f*density)
            rule.runOnIdle{assertSame(view,webView())}
        }
    }
}
