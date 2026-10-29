package ru.liftzip.mail

/** Uses consumed user scroll, so list restoration and changing toolbar heights cannot flip it. */
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
