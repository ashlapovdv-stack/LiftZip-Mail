package ru.liftzip.mail

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.roundToInt

/** One continuous scroll: header -> bounded HTML viewport -> attachments and reply controls. */
class ReaderScroll(val outer: ScrollState) {
    val toolbar=ReaderToolbarState()
    var nativeScrollY by mutableIntStateOf(0)
    var pinchActive=false
    var viewportTop=0
        set(value){bodyAnchor+=field-value;field=value}
    var viewportHeight by mutableIntStateOf(0)
    var view: MailHtmlWebView?=null
    var horizontal by mutableStateOf(HorizontalScrollMetrics())
    private var bodyAnchor=0
    fun position(top: Int){bodyAnchor=outer.value+top-viewportTop}
    val connection=object: NestedScrollConnection {
        override fun onPreScroll(available: Offset,source: NestedScrollSource): Offset {
            if(view==null || pinchActive)return Offset.Zero
            return Offset(0f,-scroll(-available.y))
        }
    }
    fun scroll(pixels: Float): Float {
        if(pinchActive)return 0f
        val body=view?:return outer.dispatchRawDelta(pixels)
        val anchor=bodyAnchor.coerceIn(0,outer.maxValue)
        var left=pixels
        // Only consume the header/footer portion preceding the body in this direction.
        val before=if(left>0)(anchor-outer.value).coerceAtLeast(0).toFloat()
            else -(outer.value-anchor).coerceAtLeast(0).toFloat()
        val step=if(left>0)minOf(left,before)else maxOf(left,before)
        left-=outer.dispatchRawDelta(step)
        left-=body.scrollContent(left.roundToInt())
        left-=outer.dispatchRawDelta(left)
        val consumed=pixels-left
        toolbar.scroll(consumed)
        return consumed
    }
    fun attach(body: MailHtmlWebView){view=body;body.scrollPage={scroll(it)}}
    fun detach(body: MailHtmlWebView){if(view===body)view=null;body.scrollPage=null}
}

data class HorizontalScrollMetrics(val offset: Int=0,val extent: Int=0,val range: Int=0)

/** Scroll-driven overlay state. It never changes the document's layout constraints. */
class ReaderToolbarState {
    var height by mutableIntStateOf(0)
    var zoomProgress by mutableFloatStateOf(0f)
    private var scrollHiddenPixels by mutableFloatStateOf(0f)
    val hiddenPixels: Float get()=maxOf(scrollHiddenPixels,zoomProgress.coerceIn(0f,1f)*height)
    fun scroll(consumedPixels: Float) {
        if(consumedPixels.isFinite())scrollHiddenPixels=(scrollHiddenPixels+consumedPixels).coerceIn(0f,height.toFloat())
    }
}
