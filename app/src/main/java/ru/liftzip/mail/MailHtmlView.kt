package ru.liftzip.mail

import android.content.Context
import android.graphics.Canvas
import android.net.http.SslError
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.OverScroller
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The app owns the resource map. A message cannot expose file/content URIs or execute code. */
class MailHtmlWebView(context: Context,private val image: (MailImageSource)->MailImageData?,private val link: (String)->Unit,
    private val heightChanged: (Int)->Unit,private val ready: ()->Unit={},private val failed: ()->Unit={},private val zoomChanged: (Float)->Unit={},
    private val pinchChanged: (Boolean)->Unit={},private val horizontalChanged: (HorizontalScrollMetrics)->Unit={},private val verticalChanged: (Int)->Unit={},
    private val waitForDocumentLayout: Boolean=false): WebView(context) {
    @Volatile private var document: MailHtmlDocument?=null
    private var renderScale=resources.displayMetrics.density
    private var initialScale: Float?=null
    private var establishingScale=false
    private var lastHeight=0
    // Document geometry in CSS pixels captured at the unzoomed base scale. The reading
    // window height is derived from it alone, never from the current pinch scale.
    private var documentHeight=0
    private var documentRevision=0L
    private var documentUrl=""
    private var visualPreparationRevision=-1L
    private var measuredInitialLayout=false
    private var layoutRevision=0L
    private var layoutPending=false
    private var awaitingInitialLayout=false
    private data class ReadingAnchor(val relativeScale: Float,val cssX: Float,val cssY: Float)
    private var pendingAnchor: ReadingAnchor?=null
    @Volatile private var live=true
    private var viewport=resources.displayMetrics.heightPixels.coerceIn(1,HtmlMail.MAX_VIEWPORT_PIXELS)
    var interactionEnabled=true
    var scrollPage: ((Float)->Float)?=null
    var onReleased: (()->Unit)?=null
    private val fling=OverScroller(context)
    private val touch=ViewConfiguration.get(context)
    private var velocity: VelocityTracker?=null
    private var startX=0f;private var startY=0f;private var lastY=0f
    private var dragging=false;private var zooming=false;private var flingY=0
    // Pinch owns the native touch stream; chrome is driven independently by scroll.
    private var pinchGestureActive=false
    private var externalPinchActive=false
    private val diagnosticsLocation=IntArray(2)
    private val animateFling=object: Runnable {
        override fun run() {
            if(!live || !fling.computeScrollOffset())return
            val delta=fling.currY-flingY;flingY=fling.currY
            if(delta!=0 && abs(movePage(delta.toFloat()))<0.5f){fling.abortAnimation();return}
            postOnAnimation(this)
        }
    }
    private fun recordGeometry(source: String,event: MotionEvent?=null) {
        getLocationOnScreen(diagnosticsLocation)
        MailZoomDiagnostics.record("webGeometry source=$source screenX=${diagnosticsLocation[0]} screenY=${diagnosticsLocation[1]} width=$width height=$height scale=$renderScale scrollX=$scrollX scrollY=$scrollY action=${event?.actionMasked?:-1} pointers=${event?.pointerCount?:0} pinch=${pinchGestureActive||externalPinchActive}")
    }
    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        scrollBarStyle=View.SCROLLBARS_OUTSIDE_OVERLAY
        isHorizontalScrollBarEnabled=false
        settings.apply {
            javaScriptEnabled=false;javaScriptCanOpenWindowsAutomatically=false;domStorageEnabled=false
            allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(false)
            blockNetworkLoads=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture=true;setGeolocationEnabled(false)
            safeBrowsingEnabled=true;cacheMode=WebSettings.LOAD_NO_CACHE
            useWideViewPort=true;loadWithOverviewMode=true
            layoutAlgorithm=WebSettings.LayoutAlgorithm.NORMAL
            builtInZoomControls=true;displayZoomControls=false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
        webViewClient=object: WebViewClient() {
            override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse {
                val resource=document?.images?.get(request.url.toString())
                val result=if(live && resource!=null)image(resource)else null
                return if(result!=null)WebResourceResponse(result.mime,null,200,"OK",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(result.bytes))
                else WebResourceResponse("image/svg+xml","UTF-8",200,"OK",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream("<svg xmlns='http://www.w3.org/2000/svg' width='240' height='48'><rect width='100%' height='100%' fill='#eef1eb'/><text x='12' y='29' font-size='14' fill='#53604e'>Изображение недоступно</text></svg>".toByteArray()))
            }
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                if(request.isForMainFrame && request.hasGesture())HtmlMail.link(request.url.toString())?.let(link)
                return true
            }
            override fun onReceivedSslError(view: WebView,handler: SslErrorHandler,error: SslError){handler.cancel()}
            override fun onReceivedHttpAuthRequest(view: WebView,handler: HttpAuthHandler,host: String,realm: String){handler.cancel()}
            override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                // The dead renderer must never take the Activity down or be reused by Compose.
                (parent as? ViewGroup)?.removeView(this@MailHtmlWebView)
                release(rendererGone=true);failed();return true
            }
            override fun onScaleChanged(view: WebView,oldScale: Float,newScale: Float){
                MailZoomDiagnostics.record("scale old=$oldScale new=$newScale webH=${view.height} scrollY=${view.scrollY} contentH=${view.contentHeight}")
                renderScale=newScale;recordGeometry("scale");publishHeight();if(establishingScale){initialScale=newScale}else initialScale?.let{base->
                val progress=((newScale/base-1.03f)/0.47f).coerceIn(0f,1f)
                post{if(live)zoomChanged(progress)}
            }}
            override fun onPageCommitVisible(view: WebView,url: String){prepareVisibleDocument(url)}
            override fun onPageFinished(view: WebView,url: String){prepareVisibleDocument(url)}
            private fun prepareVisibleDocument(url: String){
                if(!live || url!=documentUrl || visualPreparationRevision==documentRevision)return
                val revision=documentRevision
                visualPreparationRevision=revision
                postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(requestId: Long){
                    if(!live || requestId!=documentRevision)return
                    // Wide HTML can exceed the sender's declared viewport. Measure the
                    // rendered scroll extent while hidden and lay out once at that width.
                    // This is done only during the initial preparation, never on pinch.
                    if(!measuredInitialLayout && width>0 && renderScale>0f){
                        measuredInitialLayout=true
                        val extent=computeHorizontalScrollRange()
                        if(extent>width+2){
                            val cssWidth=kotlin.math.ceil(extent.toDouble()/renderScale).toInt().coerceIn(1,10000)
                            val html=document?.html
                            if(html!=null){
                                val adjusted=html.replace(Regex("(<meta name=\"viewport\" content=\"width=)[^,\"]+")){match->match.groupValues[1]+cssWidth}
                                if(adjusted!=html){
                                    documentRevision++;documentUrl="${HtmlMail.ORIGIN}document/$documentRevision/"
                                    initialScale=null;establishingScale=false;lastHeight=0;documentHeight=0
                                    setInitialScale(0);loadDataWithBaseURL(documentUrl,adjusted,"text/html","UTF-8",null)
                                    return
                                }
                            }
                        }
                    }
                    if(initialScale==null){
                        initialScale=renderScale;establishingScale=true;fitWidth()
                        postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(fitRequest: Long){
                            if(live && fitRequest==documentRevision){
                                initialScale=renderScale;establishingScale=false
                                val anchor=pendingAnchor;pendingAnchor=null
                                if(anchor!=null && kotlin.math.abs(anchor.relativeScale-1f)>0.03f){
                                    zoomBy(anchor.relativeScale.coerceIn(0.01f,100f))
                                    postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(restoreRequest: Long){
                                        if(live && restoreRequest==documentRevision){scrollTo((anchor.cssX*renderScale).roundToInt(),(anchor.cssY*renderScale).roundToInt());publishHeight();ready()}
                                    }})
                                }else {if(anchor!=null)scrollTo((anchor.cssX*renderScale).roundToInt(),(anchor.cssY*renderScale).roundToInt());awaitingInitialLayout=true;publishHeight()}
                            }
                        }})
                    }else {awaitingInitialLayout=true;publishHeight()}
                }})
            }
        }
    }
    fun show(next: MailHtmlDocument,fontScale: Float=1f) {
        if(!live)return
        val zoom=(fontScale*100).toInt().coerceIn(50,300)
        if(settings.textZoom!=zoom)settings.textZoom=zoom
        if(document===next)return
        val old=document
        val base=initialScale
        pendingAnchor=if(old!=null && old.externalAllowed!=next.externalAllowed && base!=null && base>0f && renderScale>0f)
            ReadingAnchor(renderScale/base,scrollX/renderScale,scrollY/renderScale) else null
        document=next
        documentRevision++;documentUrl="${HtmlMail.ORIGIN}document/$documentRevision/"
        layoutRevision++;layoutPending=false;awaitingInitialLayout=false
        measuredInitialLayout=false
        initialScale=null;establishingScale=false;lastHeight=0;documentHeight=0
        fling.abortAnimation();scrollTo(0,0)
        setInitialScale(0)
        loadDataWithBaseURL(documentUrl,next.html,"text/html","UTF-8",null)
    }
    fun fitWidth() {
        if(!live || width<=0)return
        // Use the rendered extent, including borders, padding and nested tables, rather than
        // assuming that the sender's nominal table width is the final page width.
        val factor=width.toFloat()/computeHorizontalScrollRange().coerceAtLeast(width)
        if(factor.isFinite() && factor>0f)zoomBy(factor.coerceIn(0.01f,100f))
    }
    fun resetZoom() {
        val base=initialScale?:return
        if(live && renderScale>0f)zoomBy((base/renderScale).coerceIn(0.01f,100f))
    }
    fun atInitialScale(): Boolean = initialScale?.let{renderScale<=it*1.03f}?:true
    fun afterVisualLayout(callback: ()->Unit) {
        val revision=documentRevision
        post {
            if(live && revision==documentRevision)postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(requestId: Long){
                if(live && revision==documentRevision)callback()
            }})
        }
    }
    fun viewport(pixels: Int) {
        val next=pixels.coerceIn(1,HtmlMail.MAX_VIEWPORT_PIXELS)
        if(viewport!=next){viewport=next;publishHeight()}
    }
    fun scrollContent(pixels: Int): Int {
        if(!live)return 0
        val before=scrollY
        val maximum=(contentHeight.toDouble()*renderScale-height).toInt().coerceAtLeast(0)
        scrollTo(scrollX,(before.toLong()+pixels).coerceIn(0,maximum.toLong()).toInt())
        return scrollY-before
    }
    private fun movePage(pixels: Float)=scrollPage?.invoke(pixels)?:scrollContent(pixels.roundToInt()).toFloat()
    private fun track(event: MotionEvent) {
        // Layout moves the WebView while its header scrolls. Velocity must use screen coordinates.
        MotionEvent.obtain(event).also{copy->copy.offsetLocation(event.rawX-event.x,event.rawY-event.y);velocity?.addMovement(copy);copy.recycle()}
    }
    private fun setPinchGesture(active: Boolean) {
        if(pinchGestureActive==active)return
        pinchGestureActive=active
        if(active){fling.abortAnimation();removeCallbacks(animateFling)}
        MailZoomDiagnostics.record("pinchState active=$active")
        pinchChanged(active)
    }
    fun setExternalPinchActive(active: Boolean) {
        if(externalPinchActive==active)return
        externalPinchActive=active
        if(active){fling.abortAnimation();removeCallbacks(animateFling)}
        MailZoomDiagnostics.record("externalPinchState active=$active")
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(!interactionEnabled)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->if(pinchGestureActive)setPinchGesture(false)
            MotionEvent.ACTION_POINTER_DOWN->if(event.pointerCount>=2){MailZoomDiagnostics.start();setPinchGesture(true)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                // The native view consumes the complete stream before Compose is allowed to
                // resume scrolling. No MotionEvent or pointer coordinate is changed.
                val result=super.dispatchTouchEvent(event)
                if(pinchGestureActive)setPinchGesture(false)
                return result
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        recordGeometry("touch",event)
        MailZoomDiagnostics.touch(event)
        if(!live)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                fling.abortAnimation();removeCallbacks(animateFling)
                velocity?.recycle();velocity=VelocityTracker.obtain();track(event)
                startX=event.rawX;startY=event.rawY;lastY=event.rawY;dragging=false;zooming=false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN->{zooming=true;dragging=false}
            MotionEvent.ACTION_MOVE->{
                track(event)
                if(!zooming && event.pointerCount==1) {
                    val dy=startY-event.rawY
                    if(!dragging && abs(dy)>touch.scaledTouchSlop && abs(dy)>abs(startX-event.rawX)) {
                        dragging=true
                        MotionEvent.obtain(event).also{it.action=MotionEvent.ACTION_CANCEL;super.onTouchEvent(it);it.recycle()}
                        lastY=startY-kotlin.math.sign(dy)*touch.scaledTouchSlop
                    }
                    if(dragging){movePage(lastY-event.rawY);lastY=event.rawY;return true}
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                track(event)
                if(dragging && event.actionMasked==MotionEvent.ACTION_UP) {
                    velocity?.computeCurrentVelocity(1000,touch.scaledMaximumFlingVelocity.toFloat())
                    val speed=-(velocity?.yVelocity?:0f).roundToInt()
                    if(abs(speed)>=touch.scaledMinimumFlingVelocity) {
                        flingY=0;fling.fling(0,0,0,speed,0,0,-Int.MAX_VALUE/4,Int.MAX_VALUE/4)
                        postOnAnimation(animateFling)
                    }
                }
                velocity?.recycle();velocity=null
                parent?.requestDisallowInterceptTouchEvent(false)
                if(dragging){dragging=false;return true}
            }
        }
        return super.onTouchEvent(event)
    }
    override fun onDraw(canvas: Canvas){super.onDraw(canvas);publishHeight();publishHorizontal()}
    override fun onScrollChanged(l: Int,t: Int,oldl: Int,oldt: Int){super.onScrollChanged(l,t,oldl,oldt);verticalChanged(t);publishHorizontal()}
    private fun publishHorizontal(){
        if(live)horizontalChanged(HorizontalScrollMetrics(computeHorizontalScrollOffset(),computeHorizontalScrollExtent(),computeHorizontalScrollRange()))
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        MailZoomDiagnostics.record("webSize w=$w h=$h oldW=$oldw oldH=$oldh scrollY=$scrollY")
        recordGeometry("size")
        if(w!=oldw || h!=oldh){
            val layout=++layoutRevision;val documentId=documentRevision
            layoutPending=true
            post {
                if(live && layout==layoutRevision && documentId==documentRevision)postVisualStateCallback(layout,object: WebView.VisualStateCallback(){override fun onComplete(requestId: Long){
                    if(live && requestId==layoutRevision && documentId==documentRevision){layoutPending=false;lastHeight=0;publishHeight()}
                }})
            }
        }
    }
    private fun publishHeight() {
        if(!live || layoutPending)return
        val content=contentHeight
        if(content<=0)return
        val base=initialScale
        // The published height is the unzoomed document geometry. A zoomed WebView window
        // is a viewport, not the letter's size: in it the reported content is stretched by
        // the window, so storing it or multiplying it by the pinch scale feeds the gesture
        // back into the layout and the history keeps sliding after the fingers leave.
        // contentHeight includes the CSS viewport's minimum height, rounded to a whole
        // CSS pixel. Never feed that viewport echo back into the document measurement.
        // Real growth (for example a decoded inline image) still exceeds that minimum.
        val unzoomed=base!=null && !establishingScale && abs(renderScale-base)<=0.000001f
        val viewportCss=kotlin.math.ceil(height.toDouble()/renderScale).toInt()
        if(base==null || (unzoomed && content>viewportCss+1))documentHeight=content
        val value=HtmlMail.height(documentHeight,base?:renderScale,viewport)
        // A wrapping reader waits for its native height to be applied. Fixed-size
        // viewports are ready after the visual commit; they never adopt this height.
        if(awaitingInitialLayout && unzoomed && (!waitForDocumentLayout || height==value)) {
            awaitingInitialLayout=false
            val revision=documentRevision
            post{if(live && revision==documentRevision)ready()}
        }
        if(value==lastHeight)return
        lastHeight=value
        val revision=documentRevision
        post{if(live && revision==documentRevision)heightChanged(value)}
    }
    fun release(rendererGone: Boolean=false){
        if(!live)return
        live=false;removeCallbacks(animateFling);fling.abortAnimation();velocity?.recycle();velocity=null
        onReleased?.invoke();onReleased=null;scrollPage=null
        if(!rendererGone)stopLoading()
        document=null;destroy()
    }
}

// The pinch viewport is the document's own reading window. Zooming must scale content inside
// that window; replacing the window with another height during the gesture makes native WebView
// resize between MOVE events and produces the observed 2104 -> 2517 -> 2104 content jumps.
internal fun readerBodyHeight(normal: Int): Int=normal

@Composable fun MailHtmlBody(message: MailItem,plain: String,hasHistory: Boolean,onInline: suspend (Attachment)->OpenedAttachment,onLink: (String)->Unit,scroll: ReaderScroll?=null,zoomed: Boolean=false,header: @Composable ()->Unit={},onZoomChanged: (Float)->Unit={},onPinchChanged: (Boolean)->Unit={}) {
    val context=LocalContext.current;val density=LocalDensity.current
    var external by rememberSaveable(message.id){mutableStateOf(false)}
    var height by remember(message.id){mutableIntStateOf(8)}
    var failed by remember(message.id,message.contentVersion){mutableStateOf(false)}
    var web by remember(message.id,message.contentVersion){mutableStateOf<MailHtmlWebView?>(null)}
    // Rich mail retains a light default canvas; sender-defined dark tables keep their own colors.
    val foreground=if(message.htmlBody.isNotBlank())"#20241f"else "#%06x".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff)
    val background=if(message.htmlBody.isNotBlank() && MaterialTheme.colorScheme.surface.luminance()<0.4f)"#f8f9f6"else "#%06x".format(MaterialTheme.colorScheme.surface.toArgb() and 0xffffff)
    val document by produceState<MailHtmlDocument?>(null,message.id,message.htmlBody,message.attachments,message.contentVersion,message.accountId,message.validity,plain,hasHistory,external,foreground,background) {
        try {
            value=withContext(Dispatchers.Default){ReaderDocuments.html.prepare(ReaderDocumentCache.Key("${message.accountId}:${message.id}:${message.validity}",message.htmlBody,plain,message.attachments,hasHistory,external,foreground,background))}
        }catch(e: CancellationException){throw e}catch(e: Exception){failed=true}
    }
    val latestInline by rememberUpdatedState(onInline)
    val latestLink by rememberUpdatedState(onLink)
    val latestZoom by rememberUpdatedState(onZoomChanged)
    val latestPinch by rememberUpdatedState(onPinchChanged)
    val latestExpanded by rememberUpdatedState(zoomed)
    val loader=remember(message.id,message.contentVersion){MailImageLoader(context.applicationContext,message.accountId){latestInline(it)}}
    DisposableEffect(loader){onDispose{loader.close()}}
    var headerHeight by remember(message.id){mutableIntStateOf(0)}
    var bodyWidth by remember(message.id){mutableIntStateOf(0)}
    val ready=remember(document,headerHeight,bodyWidth){document?.let{doc->
        if(headerHeight<=0 || bodyWidth<=0)null else doc.withReaderInset(headerHeight,bodyWidth)
    }}
    var prepared by remember(message.id,message.contentVersion,ready){mutableStateOf(false)}
    var rendered by remember(message.id,message.contentVersion,ready){mutableStateOf(false)}
    var visualRequested by remember(message.id,message.contentVersion,ready){mutableStateOf(false)}
    val latestPrepared by rememberUpdatedState({prepared=true})
    val viewport=(scroll?.viewportHeight?.takeIf{it>0}?:context.resources.displayMetrics.heightPixels).coerceIn(1,HtmlMail.MAX_VIEWPORT_PIXELS)
    Box(Modifier.fillMaxWidth().onSizeChanged{bodyWidth=it.width}) {
        if(failed)Column(Modifier.padding(top=with(density){headerHeight.toDp()})) {
            Text("Показываем текстовую версию письма",style=MaterialTheme.typography.labelMedium)
            plain.chunked(4000).forEach{Text(it,Modifier.fillMaxWidth().padding(bottom=6.dp))}
        } else if(ready!=null) {
            val windowHeight=if(prepared)viewport else height.coerceAtMost(viewport)
            // The native child always has the complete viewport after initial preparation.
            // Its unzoomed document extent alone determines where the Compose history follows.
            androidx.compose.ui.layout.Layout(content={
                key(message.id,message.contentVersion) {
                    AndroidView(factory={c->FrameLayout(c).apply {
                        try {
                            val view=MailHtmlWebView(c,loader::load,{latestLink(it)},{measured->
                                if(!latestExpanded && scroll?.pinchActive!=true && web!=null)height=measured
                            },ready={latestPrepared()},failed={failed=true;loader.close();latestZoom(0f)},zoomChanged={latestZoom(it)},pinchChanged={latestPinch(it)},horizontalChanged={scroll?.horizontal=it},verticalChanged={scroll?.nativeScrollY=it},waitForDocumentLayout=true)
                            web=view
                            view.onReleased={scroll?.detach(view);if(web===view)web=null}
                            scroll?.attach(view)
                            addView(view,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                        }catch(e: Exception){failed=true;loader.close()}
                    }},modifier=Modifier.testTag("mail-body").fillMaxWidth().height(with(density){windowHeight.toDp()})
                        .graphicsLayer{alpha=if(rendered)1f else 0f}
                        .onGloballyPositioned{coordinates->
                            scroll?.position(coordinates.positionInWindow().y.toInt())
                            MailZoomDiagnostics.record("bodyLayout zoomed=$zoomed pinch=${scroll?.pinchActive==true} top=${coordinates.positionInWindow().y} layoutH=${coordinates.size.height} webH=${scroll?.view?.height}")
                            val view=web
                            if(prepared && !visualRequested && view!=null) {
                                visualRequested=true
                                view.afterVisualLayout{rendered=true}
                            }
                        },update={host->(host.getChildAt(0) as? MailHtmlWebView)?.apply{interactionEnabled=rendered;viewport(viewport);show(ready,density.fontScale)}},
                        onRelease={host->(host.getChildAt(0) as? MailHtmlWebView)?.release();host.removeAllViews()})
                }
            }){measurables,constraints->
                val child=measurables.single().measure(constraints.copy(minHeight=0,maxHeight=androidx.compose.ui.unit.Constraints.Infinity))
                layout(child.width,height.coerceAtMost(viewport).coerceAtLeast(1)){child.placeRelative(0,0)}
            }
        }
        Column(Modifier.fillMaxWidth().onSizeChanged{headerHeight=it.height}
            .readerChrome(scroll?.toolbar?.zoomProgress?:0f,(scroll?.nativeScrollY?:0).toFloat())
            .background(androidx.compose.ui.graphics.Color.White)) {
            header()
            if(document?.externalImages==true)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={external=!external}){Text(if(external)"Скрыть внешние изображения" else "Показать изображения")}
            }
        }
        if(!failed && !rendered)Text("Подготавливаем письмо…",Modifier.padding(top=with(density){headerHeight.toDp()},bottom=14.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
