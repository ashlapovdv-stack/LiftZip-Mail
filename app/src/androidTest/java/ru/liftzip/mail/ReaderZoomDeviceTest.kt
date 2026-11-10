package ru.liftzip.mail

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
        rule.waitUntil(60_000){var ready=false;rule.runOnUiThread{ready=webView()?.let{it.contentHeight>0 && it.height>20}?:false};ready}
        var view: MailHtmlWebView?=null;rule.runOnIdle{view=webView()};return view!!
    }
    private fun center(): Offset=rule.onNodeWithTag("mail-body").fetchSemanticsNode().boundsInRoot.center

    private fun continuousOutAndBack(center: Offset,halfStart: Float,halfEnd: Float,steps: Int=24) {
        rule.onRoot().performTouchInput {
            down(0,center-Offset(halfStart,0f));down(1,center+Offset(halfStart,0f))
            for(step in 1..steps) {
                val half=halfStart+(halfEnd-halfStart)*step/steps
                moveTo(0,center-Offset(half,0f));moveTo(1,center+Offset(half,0f));move()
            }
            for(step in 1..steps) {
                val half=halfEnd+(halfStart-halfEnd)*step/steps
                moveTo(0,center-Offset(half,0f));moveTo(1,center+Offset(half,0f));move()
            }
            up(1);up(0)
        }
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
        rule.waitForIdle()
        val samples=sampler.stop()
        rule.waitForIdle()
        assertAnchorContinuous(samples,density)
        rule.runOnIdle{assertSame(view,webView())}
        rule.onNodeWithContentDescription("Предыдущие сообщения: 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Ответить или переслать").assertIsDisplayed()
    }

    @Test fun pinchFromPartiallyCollapsedPanelKeepsScreenGeometryStable() {
        rule.setContent{MailTheme{reader()}}
        val view=loaded()
        rule.waitUntil(15_000){rule.onAllNodesWithText("Подготавливаем письмо…").fetchSemanticsNodes().isEmpty()}
        var initial=0f
        rule.runOnUiThread{initial=view.scale;view.zoomBy(1.2f)}
        rule.waitUntil(10_000){var enlarged=false;rule.runOnUiThread{enlarged=view.scale>initial*1.05f};enlarged}
        rule.waitForIdle()
        val focus=center()
        val sampler=FrameSampler(view,focus.y);sampler.start()
        continuousOutAndBack(focus,48f*view.resources.displayMetrics.density,160f*view.resources.displayMetrics.density)
        Thread.sleep(250)
        rule.waitForIdle()
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
        rule.onRoot().performTouchInput {
            down(0,focus-Offset(48f*density,0f));down(1,focus+Offset(48f*density,0f))
            for(step in 1..16) {
                val half=(48f+22f*step/16)*density
                moveTo(0,focus-Offset(half,0f));moveTo(1,focus+Offset(half,0f));move()
            }
            // The UI thread remains free while both fingers are stationary at partial collapse.
            Thread.sleep(400)
            move()
            up(1);up(0)
        }
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
