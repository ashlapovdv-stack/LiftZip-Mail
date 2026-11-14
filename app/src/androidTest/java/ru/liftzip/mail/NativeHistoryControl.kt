package ru.liftzip.mail

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.jsoup.Jsoup

/** Tests interact with the actual WebView accessibility button, not a Compose proxy. */
internal class NativeHistoryControl(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    private val label: String
) {
    private fun node(): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            root ?: return null
            if(root.contentDescription?.toString()==label || root.text?.toString()==label)return root
            for(i in 0 until root.childCount)find(root.getChild(i))?.let{return it}
            return null
        }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        // Native page replacement invalidates Chromium virtual node IDs. Query a
        // fresh accessibility snapshot rather than a cached pre-navigation tree.
        if(android.os.Build.VERSION.SDK_INT>=34)automation.clearCache()
        return find(automation.rootInActiveWindow)
    }
    fun exists() = node()!=null
    fun assertExists()=apply {
        try {
            rule.waitUntil(15_000){exists()}
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // Distinguish missing HTML from a Chromium accessibility bridge failure.
            rule.runOnUiThread {
                fun locate(v: View): MailHtmlWebView? {
                    if(v is MailHtmlWebView)return v
                    if(v is ViewGroup)for(i in 0 until v.childCount)locate(v.getChildAt(i))?.let{return it}
                    return null
                }
                val view=locate(rule.activity.window.decorView)
                val document=runCatching {
                    val field=MailHtmlWebView::class.java.getDeclaredField("document").apply{isAccessible=true}
                    field.get(view) as? MailHtmlDocument
                }.getOrNull()
                val html=document?.html.orEmpty()
                android.util.Log.e("MailHistoryDiagnosis",
                    "label=$label webView=${view!=null} attached=${view?.isAttachedToWindow} " +
                    "progress=${view?.progress} enabled=${view?.interactionEnabled} " +
                    "generation=${view?.documentGeneration()} htmlLength=${html.length} " +
                    "labelInHtml=${html.contains(label)} historyLinks=${org.jsoup.Jsoup.parse(html).select("a[aria-expanded]").size}")
            }
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            if(android.os.Build.VERSION.SDK_INT>=34)automation.clearCache()
            val root=automation.rootInActiveWindow
            val descriptions=mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?,depth: Int) {
                if(node==null || depth>12 || descriptions.size>=80)return
                val value=node.contentDescription?.toString() ?: node.text?.toString()
                if(!value.isNullOrBlank())descriptions+=value.take(90)
                for(i in 0 until node.childCount)collect(node.getChild(i),depth+1)
            }
            collect(root,0)
            android.util.Log.e("MailHistoryDiagnosis","accessibilityRoot=${root!=null} labels=${descriptions.joinToString(" | ")}")
            throw failure
        }
    }
    fun assertIsDisplayed()=apply { assertExists();assertTrue(node()!!.isVisibleToUser) }
    fun performScrollTo()=apply {
        if(!exists()) {
            // Chromium exposes only nearby virtual nodes. Find scrolls the real
            // document to this text before requesting its accessibility action.
            var view: MailHtmlWebView?=null
            rule.runOnUiThread {
                fun find(v: View): MailHtmlWebView? {
                    if(v is MailHtmlWebView)return v
                    if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
                    return null
                }
                view=find(rule.activity.window.decorView)
                view?.setFindListener{active,total,done->
                    android.util.Log.e("MailHistoryDiagnosis","findCallback active=$active total=$total done=$done")
                    if(done && total>0){view?.setFindListener(null);view?.findNext(true)}
                }
                view?.findAllAsync("⋯ "+label.substringAfterLast(": "))
            }
            assertExists()
            rule.runOnUiThread{view?.setFindListener(null);view?.clearMatches()}
        }
        assertExists()
        node()!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        rule.waitForIdle()
    }
    fun performClick()=apply {
        assertIsDisplayed()
        assertTrue("Native history button must accept clicks",node()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitForIdle()
    }
    data class Bounds(val boundsInRoot: ComposeRect)
    fun fetchSemanticsNode(): Bounds {
        assertExists()
        val r=Rect();node()!!.getBoundsInScreen(r)
        val origin=IntArray(2);rule.runOnUiThread{rule.activity.window.decorView.getLocationOnScreen(origin)}
        val root=rule.onRoot().fetchSemanticsNode().boundsInWindow
        return Bounds(ComposeRect(r.left-origin[0]-root.left,r.top-origin[1]-root.top,r.right-origin[0]-root.left,r.bottom-origin[1]-root.top))
    }
    fun assertExpanded(expanded: Boolean)=apply {
        rule.waitUntil(15_000) {
            var result=false
            rule.runOnUiThread {
                fun find(view: View): MailHtmlWebView? {
                    if(view is MailHtmlWebView)return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                    return null
                }
                val view=find(rule.activity.window.decorView)
                if(view!=null && view.interactionEnabled) {
                    val field=MailHtmlWebView::class.java.getDeclaredField("document").apply{isAccessible=true}
                    val document=field.get(view) as? MailHtmlDocument
                    result=document?.let{Jsoup.parse(it.html).selectFirst("a[aria-expanded]")?.attr("aria-expanded")==expanded.toString()}==true
                }
            }
            result
        }
    }
}
