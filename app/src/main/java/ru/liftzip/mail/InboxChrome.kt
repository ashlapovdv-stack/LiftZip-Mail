package ru.liftzip.mail

/** Receives user input before layout, so resizing panels cannot generate a direction reversal. */
class InboxChromeController(var visible: Boolean=true,private val hideDistance: Float=24f,private val showDistance: Float=8f) {
    private var distance=0f
    fun show(){visible=true;distance=0f}
    fun scroll(delta: Float,forced: Boolean=false): Boolean {
        if(forced){show();return true}
        if(delta==0f)return visible
        if(delta*distance<0)distance=0f
        distance+=delta
        if(visible && distance<=-hideDistance){visible=false;distance=0f}
        else if(!visible && distance>=showDistance){visible=true;distance=0f}
        return visible
    }
}

data class NoticeAnchor(val owner: String,val top: Float)

object InboxInteractionRules {
    fun panelHeight(height: Int,fraction: Float)=kotlin.math.round(height*fraction.coerceIn(0f,1f)).toInt().coerceIn(0,height)
    fun pullProgress(distance: Float,refreshing: Boolean,accepted: Boolean)=if(refreshing || accepted)0f else distance.coerceIn(0f,1f)
    fun noticeBottom(rootHeight: Int,route: String,anchor: NoticeAnchor?,safeBottom: Int,gap: Int): Int {
        val obstruction=anchor?.takeIf{it.owner==route && it.top>=0f && it.top<rootHeight}?.let{rootHeight-it.top.toInt()}?:0
        return maxOf(safeBottom,obstruction).coerceAtLeast(0)+gap
    }
}
