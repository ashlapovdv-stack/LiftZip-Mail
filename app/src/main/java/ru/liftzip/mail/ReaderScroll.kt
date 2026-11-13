package ru.liftzip.mail

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.roundToInt

/** One continuous scroll: header -> bounded HTML viewport -> attachments and reply controls. */
class ReaderScroll(val outer: ScrollState) {
    val toolbar=ReaderToolbarState{chromeZoomProgress}
    var zoomProgress by mutableFloatStateOf(0f)
    var nativeScaleRatio by mutableFloatStateOf(1f)
    var headerHeight by mutableIntStateOf(0)
    // Match the overlay's lower edge to the natively scaled/scrolled document inset.
    // No native scroll position, touch coordinate or viewport constraint is changed here.
    val headerOffset: Float get()=nativeScrollY-headerHeight*(nativeScaleRatio-1f)
    // Visibility follows native zoom progress even when the focal point lies
    // inside the document inset. Native scroll only positions the overlay.
    val chromeZoomProgress: Float get()=zoomProgress.coerceIn(0f,1f)
    val chromeOffset: Float get()=maxOf(headerOffset,headerHeight*chromeZoomProgress,0f)
    var nativeScrollY by mutableIntStateOf(0)
    var pinchActive=false
    var contentTop=0f
    var contentBottom=0f
    var viewportTop=0
        set(value){val shift=field-value;windows.replaceAll{_,anchor->anchor+shift};field=value}
    var viewportHeight by mutableIntStateOf(0)
    var view: MailHtmlWebView?=null
    var horizontal by mutableStateOf(HorizontalScrollMetrics())
    private val windows=LinkedHashMap<MailHtmlWebView,Int>()
    fun position(body: MailHtmlWebView,top: Int){if(body in windows)windows[body]=outer.value+top-viewportTop}
    val connection=object: NestedScrollConnection {
        override fun onPreScroll(available: Offset,source: NestedScrollSource): Offset {
            if(view==null || pinchActive)return Offset.Zero
            return Offset(0f,-scroll(-available.y))
        }
    }
    fun scroll(pixels: Float): Float {
        if(pinchActive)return 0f
        if(windows.isEmpty())return outer.dispatchRawDelta(pixels)
        var left=pixels
        // Visit every native window in document order (reverse order on the way back).
        // Each window scrolls internally only after its preceding Compose extent has
        // passed. Unused movement continues to the next window and the final footer.
        val ordered=windows.entries.sortedBy{it.value}.let{if(pixels<0)it.asReversed()else it}
        for((body,position) in ordered) {
            val anchor=position.coerceIn(0,outer.maxValue)
            val before=if(left>0)(anchor-outer.value).coerceAtLeast(0).toFloat()
                else -(outer.value-anchor).coerceAtLeast(0).toFloat()
            val step=if(left>0)minOf(left,before)else maxOf(left,before)
            left-=outer.dispatchRawDelta(step)
            left-=body.scrollContent(left.roundToInt())
            if(kotlin.math.abs(left)<0.5f)break
        }
        left-=outer.dispatchRawDelta(left)
        val consumed=pixels-left
        toolbar.scroll(consumed)
        return consumed
    }
    fun attach(body: MailHtmlWebView,primary: Boolean=true){if(primary)view=body;windows[body]=0;body.scrollPage={scroll(it)}}
    fun detach(body: MailHtmlWebView){if(view===body)view=null;windows.remove(body);body.scrollPage=null}
}

data class HorizontalScrollMetrics(val offset: Int=0,val extent: Int=0,val range: Int=0)

/** Scroll-driven overlay state. It never changes the document's layout constraints. */
class ReaderToolbarState(private val zoom: ()->Float={0f}) {
    var height by mutableIntStateOf(0)
    val zoomProgress: Float get()=zoom()
    private var scrollHiddenPixels by mutableFloatStateOf(0f)
    val hiddenPixels: Float get()=maxOf(scrollHiddenPixels,zoomProgress.coerceIn(0f,1f)*height)
    fun scroll(consumedPixels: Float) {
        if(consumedPixels.isFinite())scrollHiddenPixels=(scrollHiddenPixels+consumedPixels).coerceIn(0f,height.toFloat())
    }
    /** Collapsing a long quote changes the scrollable extent without a scroll event. */
    fun onHistoryCollapsed() {
        scrollHiddenPixels=0f
    }
}
