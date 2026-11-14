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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.HideImage
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
    private var waitForDocumentLayout: Boolean=false): WebView(context) {
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
    private var deferredAnchor: ReadingAnchor?=null
    @Volatile private var live=true
    private var viewport=resources.displayMetrics.heightPixels.coerceIn(1,HtmlMail.MAX_VIEWPORT_PIXELS)
    var ownsTouchStream=false
        private set
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
        if(!MailZoomDiagnostics.isRecording)return
        getLocationOnScreen(diagnosticsLocation)
        MailZoomDiagnostics.record {"webGeometry source=$source screenX=${diagnosticsLocation[0]} screenY=${diagnosticsLocation[1]} width=$width height=$height scale=$renderScale scrollX=$scrollX scrollY=$scrollY action=${event?.actionMasked?:-1} pointers=${event?.pointerCount?:0} pinch=${pinchGestureActive||externalPinchActive}"}
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
                MailZoomDiagnostics.record {"scale old=$oldScale new=$newScale webH=${view.height} scrollY=${view.scrollY} contentH=${view.contentHeight}"}
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
                    if(document?.originalScale!=true && !measuredInitialLayout && width>0 && renderScale>0f){
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
                        initialScale=renderScale;establishingScale=true
                        if(document?.originalScale==true)zoomBy((resources.displayMetrics.density/renderScale).coerceIn(0.01f,100f))else fitWidth()
                        postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(fitRequest: Long){
                            if(live && fitRequest==documentRevision){
                                initialScale=renderScale;establishingScale=false
                                val anchor=pendingAnchor;pendingAnchor=null
                                if(anchor!=null && kotlin.math.abs(anchor.relativeScale-1f)>0.03f){
                                    zoomBy(anchor.relativeScale.coerceIn(0.01f,100f))
                                    postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(restoreRequest: Long){
                                        if(live && restoreRequest==documentRevision){restoreReadingAnchor(anchor);publishHeight();ready()}
                                    }})
                                }else {if(anchor!=null)restoreReadingAnchor(anchor);awaitingInitialLayout=true;publishHeight()}
                            }
                        }})
                    }else {awaitingInitialLayout=true;publishHeight()}
                }})
            }
        }
    }
    private fun restoreReadingAnchor(anchor: ReadingAnchor) {
        // A mode change can shrink the native extent below the saved zoomed
        // position. View.scrollTo does not enforce document bounds: restoring
        // that coordinate would scroll the header past an unscrollable letter.
        // Clamp only after this revision's scale and layout have committed.
        val maxX=(computeHorizontalScrollRange()-width).coerceAtLeast(0)
        val maxY=(contentHeight.toDouble()*renderScale-height).toInt().coerceAtLeast(0)
        scrollTo((anchor.cssX*renderScale).roundToInt().coerceIn(0,maxX),
            (anchor.cssY*renderScale).roundToInt().coerceIn(0,maxY))
    }

    fun show(next: MailHtmlDocument,fontScale: Float=1f) {
        if(!live)return
        val zoom=(fontScale*100).toInt().coerceIn(50,300)
        if(settings.textZoom!=zoom)settings.textZoom=zoom
        if(document===next)return
        val old=document
        val base=initialScale
        val modeChanged=old!=null && old.originalScale!=next.originalScale
        if(modeChanged)deferredAnchor=if(renderScale>0f)ReadingAnchor(1f,scrollX/renderScale,scrollY/renderScale)else null
        // Intrinsic measurement stays at base scale. Restore the reading anchor
        // only after the fixed native canvas is installed.
        pendingAnchor=if(waitForDocumentLayout)null else {
            deferredAnchor?:pendingAnchor?:if(old!=null && base!=null && base>0f && renderScale>0f)
                ReadingAnchor(renderScale/base,scrollX/renderScale,scrollY/renderScale) else null
        }
        if(!waitForDocumentLayout)deferredAnchor=null
        document=next
        documentRevision++;documentUrl="${HtmlMail.ORIGIN}document/$documentRevision/"
        layoutRevision++;layoutPending=false;awaitingInitialLayout=false
        measuredInitialLayout=false
        initialScale=null;establishingScale=false;lastHeight=0;documentHeight=0
        if(modeChanged)post{if(live)zoomChanged(0f)}
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
        if(!live || renderScale<=0f)return
        val ratio=base/renderScale
        if(!ratio.isFinite() || kotlin.math.abs(ratio-1f)<0.001f)return
        // Preserve the document point at the centre of the reading viewport.
        // Chromium's default zoom focal point can land in the oversized reader
        // inset when zooming back in from a scale below the original.
        val anchorX=(scrollX+width/2f)/renderScale
        val anchorY=(scrollY+height/2f)/renderScale
        val revision=documentRevision
        zoomBy(ratio.coerceIn(0.01f,100f))
        postVisualStateCallback(revision,object: WebView.VisualStateCallback(){
            override fun onComplete(requestId: Long) {
                if(!live || requestId!=documentRevision || width<=0 || height<=0)return
                val maxX=(computeHorizontalScrollRange()-width).coerceAtLeast(0)
                val maxY=(contentHeight.toDouble()*renderScale-height).toInt().coerceAtLeast(0)
                scrollTo((anchorX*renderScale-width/2f).roundToInt().coerceIn(0,maxX),
                    (anchorY*renderScale-height/2f).roundToInt().coerceIn(0,maxY))
            }
        })
    }
    internal fun fittedScale(): Float=initialScale?:renderScale
    internal fun documentGeneration(): Long=documentRevision
    fun relativeScale(): Float=initialScale?.takeIf{it>0f}?.let{renderScale/it}?:1f
    fun atInitialScale(): Boolean = initialScale?.let{renderScale<=it*1.03f}?:true
    fun measuringDocument(enabled: Boolean) {
        if(enabled && !waitForDocumentLayout) {
            val base=initialScale
            if(document!=null && base!=null && base>0f && renderScale>0f)
                deferredAnchor=ReadingAnchor(renderScale/base,scrollX/renderScale,scrollY/renderScale)
        }
        waitForDocumentLayout=enabled
    }
    fun afterVisualLayout(callback: ()->Unit) {
        if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","request visual layout h=$height")
        val revision=documentRevision
        post {
            if(live && revision==documentRevision)postVisualStateCallback(revision,object: WebView.VisualStateCallback(){override fun onComplete(requestId: Long){
                if(live && revision==documentRevision){if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","visual layout ready h=$height");callback()}
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
        MailZoomDiagnostics.record {"pinchState active=$active"}
        pinchChanged(active)
    }
    fun setExternalPinchActive(active: Boolean) {
        if(externalPinchActive==active)return
        externalPinchActive=active
        if(active){fling.abortAnimation();removeCallbacks(animateFling)}
        MailZoomDiagnostics.record {"externalPinchState active=$active"}
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(!interactionEnabled)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{ownsTouchStream=true;if(pinchGestureActive)setPinchGesture(false)}
            MotionEvent.ACTION_POINTER_DOWN->if(event.pointerCount>=2){MailZoomDiagnostics.start();setPinchGesture(true)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                // The native view consumes the complete stream before Compose is allowed to
                // resume scrolling. No MotionEvent or pointer coordinate is changed.
                val result=super.dispatchTouchEvent(event)
                ownsTouchStream=false
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
        MailZoomDiagnostics.record {"webSize w=$w h=$h oldW=$oldw oldH=$oldh scrollY=$scrollY"}
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
        if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","height=$height document=$documentHeight content=$content base=$base scale=$renderScale value=$value awaiting=$awaitingInitialLayout pending=$layoutPending")
        lastHeight=value
        val revision=documentRevision
        post{if(live && revision==documentRevision)heightChanged(value)}
    }
    fun release(rendererGone: Boolean=false){
        if(!live)return
        live=false;ownsTouchStream=false;removeCallbacks(animateFling);fling.abortAnimation();velocity?.recycle();velocity=null
        onReleased?.invoke();onReleased=null;scrollPage=null
        if(!rendererGone)stopLoading()
        document=null;destroy()
    }
}

// The pinch viewport is the document's own reading window. Zooming must scale content inside
// that window; replacing the window with another height during the gesture makes native WebView
// resize between MOVE events and produces the observed 2104 -> 2517 -> 2104 content jumps.
internal fun readerBodyHeight(normal: Int): Int=normal

@Composable fun MailHtmlBody(message: MailItem,plain: String,history: HtmlMail.HistoryAnalysis,onInline: suspend (Attachment)->OpenedAttachment,onLink: (String)->Unit,scroll: ReaderScroll?=null,zoomed: Boolean=false,header: @Composable ()->Unit={},onZoomChanged: (Float)->Unit={},onPinchChanged: (Boolean)->Unit={},rendererFailed: MutableState<Boolean> = remember(message.id,message.contentVersion){mutableStateOf(false)},originalScale: Boolean=false,onContentReady: (() -> Unit)?=null,parentScroll: ReaderScroll?=null,afterContent: @Composable ()->Unit={},externalImageState: MutableState<Boolean>?=null,onImageAvailability: (Boolean)->Unit={},showImageControl: Boolean=true) {
    val context=LocalContext.current;val density=LocalDensity.current
    var localExternal by rememberSaveable(message.id){mutableStateOf(false)}
    val external=externalImageState?.value?:localExternal
    val toggleExternal: ()->Unit={if(externalImageState!=null)externalImageState.value=!external else localExternal=!external}
    var failed by rendererFailed
    var web by remember(message.id,message.contentVersion){mutableStateOf<MailHtmlWebView?>(null)}
    // Rich mail retains a light default canvas; sender-defined dark tables keep their own colors.
    val foreground=if(message.htmlBody.isNotBlank())"#20241f"else "#%06x".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff)
    val background=if(message.htmlBody.isNotBlank() && MaterialTheme.colorScheme.surface.luminance()<0.4f)"#f8f9f6"else "#%06x".format(MaterialTheme.colorScheme.surface.toArgb() and 0xffffff)
    val documentKey=remember(message.id,message.htmlBody,message.attachments,message.contentVersion,message.accountId,message.validity,plain,history,external,foreground,background,originalScale){
        ReaderDocumentCache.Key("${message.accountId}:${message.id}:${message.validity}",message.htmlBody,plain,message.attachments,history,external,foreground,background,originalScale)
    }
    val cachedDocument=remember(documentKey){ReaderDocuments.html.peek(documentKey)}
    // Keep the current native host while the next document is prepared. A null
    // transition would dispose the WebView and discard its reading/zoom anchor.
    val preparedDocument by key(message.id,message.contentVersion){produceState(cachedDocument,documentKey) {
        withContext(Dispatchers.Main.immediate) {
            try {value=withContext(Dispatchers.Default){ReaderDocuments.html.prepare(documentKey)}}
            catch(e: CancellationException){throw e}catch(e: Exception){failed=true}
        }
    }}
    val document=cachedDocument?:preparedDocument
    val latestAvailability by rememberUpdatedState(onImageAvailability)
    LaunchedEffect(document?.externalImages){latestAvailability(document?.externalImages==true)}
    val latestInline by rememberUpdatedState(onInline)
    val latestLink by rememberUpdatedState(onLink)
    val latestZoom by rememberUpdatedState(onZoomChanged)
    val latestPinch by rememberUpdatedState(onPinchChanged)
    val loader=remember(message.id,message.contentVersion){MailImageLoader(context.applicationContext,message.accountId){latestInline(it)}}
    DisposableEffect(loader){onDispose{loader.close()}}
    var headerHeight by remember(message.id){mutableIntStateOf(0)}
    var headerMeasured by remember(message.id){mutableStateOf(false)}
    var bodyWidth by remember(message.id){mutableIntStateOf(0)}
    val scrollOwner=scroll?:parentScroll
    val viewportMeasured=scrollOwner==null || scrollOwner.viewportHeight>0
    val ready=remember(document,bodyWidth,viewportMeasured){document?.let{doc->
        if(bodyWidth<=0 || !viewportMeasured)null else doc.withReaderInset(0,bodyWidth)
    }}
    val viewport=(scrollOwner?.viewportHeight?.takeIf{it>0}?:context.resources.displayMetrics.heightPixels).coerceIn(1,HtmlMail.MAX_VIEWPORT_PIXELS)
    val geometryKey=remember(document,bodyWidth,density.density,density.fontScale,viewport,viewportMeasured){
        document?.takeIf{bodyWidth>0 && viewportMeasured}?.let{ReaderGeometryCache.Key(it,bodyWidth,density.density,density.fontScale,viewport)}
    }
    val cachedGeometry=remember(geometryKey){geometryKey?.let{ReaderDocuments.geometry.peek(it)}}
    var intrinsicHeight by remember(message.id,message.contentVersion,ready,geometryKey){mutableIntStateOf(cachedGeometry?.height?:8)}
    var baseScale by remember(message.id,message.contentVersion,ready,geometryKey){mutableFloatStateOf(cachedGeometry?.scale?:1f)}
    var prepared by remember(message.id,message.contentVersion,ready,geometryKey){mutableStateOf(cachedGeometry!=null)}
    var nativeCommit by remember(message.id,message.contentVersion,ready,geometryKey){mutableIntStateOf(0)}
    var rendered by remember(message.id,message.contentVersion,ready,geometryKey){mutableStateOf(false)}
    var followingContentPublished by remember(message.id,message.contentVersion){mutableStateOf(false)}
    SideEffect{if(rendered || failed)followingContentPublished=true}
    val latestContentReady by rememberUpdatedState(onContentReady)
    LaunchedEffect(rendered,failed){if(rendered || failed)latestContentReady?.invoke()}
    val latestMeasuring by rememberUpdatedState(!prepared)
    val latestMeasured by rememberUpdatedState<(Int)->Unit>({measured->
        if(latestMeasuring && scroll?.pinchActive!=true && web!=null)intrinsicHeight=measured
    })
    val latestPrepared by rememberUpdatedState({
        if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","document prepared measuring=${!prepared}")
        if(!prepared) {
            baseScale=web?.fittedScale()?:1f
            prepared=true
        } else nativeCommit++
    })
    // Keep the measured HTML extent separate from the Compose header. A late
    // measurement must never overwrite or double-add the header contribution.
    val height=intrinsicHeight+if(prepared)headerHeight else 0
    val nativeDocument=remember(ready,prepared,viewport,bodyWidth,baseScale,headerHeight){ready?.let{doc->
        if(prepared)document?.withReaderInset(headerHeight,bodyWidth,baseScale)?.withReaderCanvas(viewport,bodyWidth,baseScale) else doc
    }}
    LaunchedEffect(nativeCommit,web,ready,headerHeight,bodyWidth) {
        if(nativeCommit>0)web?.afterVisualLayout{rendered=true}
    }
    LaunchedEffect(rendered,geometryKey,intrinsicHeight,baseScale) {
        if(rendered && prepared)geometryKey?.let{ReaderDocuments.geometry.put(it,ReaderGeometryCache.Geometry(intrinsicHeight,baseScale))}
    }
    SideEffect{if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","compose document=${document!=null} ready=${ready!=null} header=$headerHeight width=$bodyWidth prepared=$prepared commit=$nativeCommit rendered=$rendered failed=$failed")}
    Column {
    Box(Modifier.fillMaxWidth().onSizeChanged{bodyWidth=it.width}) {
        if(failed)Column(Modifier.padding(top=with(density){headerHeight.toDp()})) {
            Text("Показываем текстовую версию письма",style=MaterialTheme.typography.labelMedium)
            // Rendering input may be a current-reply projection; fallback always owns the complete letter.
            message.body.ifBlank{"В письме нет доступного текстового содержимого."}.chunked(4000).forEach{Text(it,Modifier.fillMaxWidth().padding(bottom=6.dp))}
        } else if(ready!=null && headerMeasured) {
            val windowHeight=if(prepared)viewport else height.coerceAtMost(viewport)
            val contentTop=(headerHeight-(scroll?.headerOffset?:0f)).coerceIn(0f,viewport.toFloat()).roundToInt()
            val contentHeight=((if(zoomed)viewport else height.coerceAtMost(viewport))-contentTop).coerceAtLeast(0)
            // The native child always has the complete viewport after initial preparation.
            // Its unzoomed document extent alone determines where the Compose history follows.
            androidx.compose.ui.layout.Layout(content={
                key(message.id,message.contentVersion) {
                    AndroidView(factory={c->FrameLayout(c).apply {
                        try {
                            val view=MailHtmlWebView(c,loader::load,{latestLink(it)},{latestMeasured(it)},ready={latestPrepared()},failed={failed=true;loader.close();latestZoom(0f)},zoomChanged={progress->scroll?.nativeScaleRatio=web?.relativeScale()?:1f;latestZoom(progress)},pinchChanged={latestPinch(it)},horizontalChanged={scroll?.horizontal=it},verticalChanged={scroll?.nativeScrollY=it},waitForDocumentLayout=true)
                            web=view
                            view.onReleased={scrollOwner?.detach(view);if(web===view)web=null}
                            scrollOwner?.attach(view,primary=scroll!=null)
                            addView(view,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                        }catch(e: Exception){android.util.Log.e("MailReaderReady","create failed",e);failed=true;loader.close()}
                    }},modifier=Modifier.testTag("mail-native-window").fillMaxWidth().height(with(density){windowHeight.toDp()})
                        .graphicsLayer{alpha=if(rendered)1f else 0f}
                        .onGloballyPositioned{coordinates->
                            web?.let{scrollOwner?.position(it,coordinates.positionInWindow().y.toInt())}
                            MailZoomDiagnostics.record {"bodyLayout zoomed=$zoomed pinch=${scroll?.pinchActive==true} top=${coordinates.positionInWindow().y} layoutH=${coordinates.size.height} webH=${scroll?.view?.height}"}
                            val view=web
                            if(MailZoomDiagnostics.isEnabled)android.util.Log.i("MailReaderReady","layout prepared=$prepared rendered=$rendered commit=$nativeCommit normal=$height window=$windowHeight native=${view?.height} header=$headerHeight")
                        },update={host->(host.getChildAt(0) as? MailHtmlWebView)?.apply{interactionEnabled=rendered;measuringDocument(!prepared);viewport(viewport);show(nativeDocument?:ready,density.fontScale)}},
                        onRelease={host->(host.getChildAt(0) as? MailHtmlWebView)?.release();host.removeAllViews()})
                }
                Spacer(Modifier.testTag(if(rendered)"mail-body" else "mail-preparing-content").onGloballyPositioned{coordinates->
                    scroll?.contentTop=coordinates.positionInWindow().y
                    scroll?.contentBottom=coordinates.positionInWindow().y+coordinates.size.height
                }.fillMaxWidth().height(with(density){contentHeight.toDp()}))
            }){measurables,constraints->
                val unbounded=constraints.copy(minHeight=0,maxHeight=androidx.compose.ui.unit.Constraints.Infinity)
                val child=measurables[0].measure(unbounded)
                val contentBounds=measurables[1].measure(unbounded)
                layout(child.width,height.coerceAtMost(viewport).coerceAtLeast(1)){
                    child.placeRelative(0,0)
                    contentBounds.placeRelative(0,contentTop)
                }
            }
        }
        Column(Modifier.fillMaxWidth().onSizeChanged{headerHeight=it.height;headerMeasured=true;scroll?.headerHeight=it.height}
            .readerChrome(scroll?.toolbar?.zoomProgress?:0f,scroll?.headerOffset?:0f).testTag("reader-message-header")
            .background(androidx.compose.ui.graphics.Color.White)) {
            header()
            if(showImageControl && document?.externalImages==true)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.End) {
                IconButton(onClick=toggleExternal) {
                    Icon(if(external) Icons.Outlined.HideImage else Icons.Outlined.Image,
                        contentDescription=if(external) "Скрыть внешние изображения" else "Показать изображения",
                        tint=MaterialTheme.colorScheme.primary)
                }
            }
        }
        // The main reader owns one opening timer across storage, analysis and native
        // rendering. Independent quote/original views own only their own preparation.
        val slowPreparation=rememberReaderProgress(documentKey,onContentReady==null && !rendered && !failed)
        if(slowPreparation)CircularProgressIndicator(
            Modifier.padding(top=with(density){headerHeight.toDp()},start=16.dp).size(20.dp).testTag("reader-preparation-progress"),strokeWidth=2.dp)
    }
    // Publish following controls with the first visible document, not with its
    // measurement window. Keep them mounted during zoom/layout changes of that
    // same source, preserving expanded citation views and the outer scroll extent.
    if(followingContentPublished || rendered || failed)afterContent()
    }
}
