package ru.liftzip.mail

/** Scroll position, with no clock, threshold, spring, or release-time snapping. */
class InboxChromeController(initialFraction: Float=1f) {
    var fraction=initialFraction.takeIf{it.isFinite()}?.coerceIn(0f,1f)?:1f
        private set
    private var headerHeight=0f
    private var flingActive=false
    fun measureHeader(height: Float){headerHeight=height.takeIf{it.isFinite() && it>0f}?:0f}
    fun show(){fraction=1f}
    fun beginFling(velocity: Float){flingActive=velocity.isFinite() && kotlin.math.abs(velocity)>0.01f}
    fun endFling(){flingActive=false}
    /** Consume only the movement used to shift the list's viewport. LazyColumn gets the rest,
     * otherwise the same drag would move the rows twice as far as the finger. */
    fun scroll(delta: Float,forced: Boolean=false,userInput: Boolean=true): Float {
        if(forced){show();return 0f}
        if((!userInput && !flingActive) || !delta.isFinite() || delta==0f || headerHeight<=0f)return 0f
        val before=fraction*headerHeight
        val consumed=delta.coerceIn(-before,headerHeight-before)
        fraction=((before+consumed)/headerHeight).coerceIn(0f,1f)
        return consumed
    }
}

data class NoticeAnchor(val owner: String,val top: Float)

object InboxInteractionRules {
    fun viewport(height: Int,header: Int,bottom: Int,fraction: Float): InboxViewportGeometry {
        val offset=kotlin.math.round(header*fraction.coerceIn(0f,1f)).toInt()
        return InboxViewportGeometry(height,offset,height-bottom,offset+bottom)
    }
    fun pullProgress(distance: Float,refreshing: Boolean,accepted: Boolean,gestureArmed: Boolean)=if(refreshing || accepted || !gestureArmed)0f else distance.coerceIn(0f,1f)
    fun noticeBottom(rootHeight: Int,route: String,anchor: NoticeAnchor?,safeBottom: Int,gap: Int): Int {
        val obstruction=anchor?.takeIf{it.owner==route && it.top>=0f && it.top<rootHeight}?.let{rootHeight-it.top.toInt()}?:0
        return maxOf(safeBottom,obstruction).coerceAtLeast(0)+gap
    }
}

data class InboxViewportGeometry(val listHeight: Int,val listY: Int,val bottomY: Int,val listBottomPadding: Int)

/** Space available with both panels fully visible, independent of the current fade. */
data class InboxPanelMetrics(val viewportHeight: Int,val headerHeight: Int,val bottomHeight: Int,val edgeGap: Int=0) {
    val contentHeight get()=(viewportHeight-headerHeight-bottomHeight-edgeGap).coerceAtLeast(0)
}

/** Lazy rows are measured incrementally. A lower bound is enough to identify a long list;
 * short lists become definite only when every row has been observed. */
class InboxContentMeasure(private val expectedKeys: Set<Any>) {
    private val sizes=mutableMapOf<Any,Int>()
    private var extent=0L
    fun observe(totalItems: Int,available: Int,items: List<Pair<Any,Int>>): Boolean? {
        if(totalItems!=expectedKeys.size || totalItems<=0 || available<=0 || items.isEmpty() || items.any{it.first !in expectedKeys})return null
        for((key,size) in items) {
            val height=size.coerceAtLeast(0)
            extent+=height-(sizes.put(key,height)?:0)
        }
        if(extent>available)return true
        return if(sizes.size==expectedKeys.size)false else null
    }
}
