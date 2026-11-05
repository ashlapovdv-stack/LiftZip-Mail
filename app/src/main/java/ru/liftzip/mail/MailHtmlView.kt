package ru.liftzip.mail

import android.content.Context
import android.graphics.Canvas
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app owns the resource map. A message cannot expose file/content URIs or execute code. */
class MailHtmlWebView(context: Context,private val image: (MailImageSource)->MailImageData?,private val link: (String)->Unit,
    private val heightChanged: (Int)->Unit,private val ready: ()->Unit={}): WebView(context) {
    @Volatile private var document: MailHtmlDocument?=null
    private var renderScale=resources.displayMetrics.density
    private var lastHeight=0
    @Volatile private var live=true
    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled=false;javaScriptCanOpenWindowsAutomatically=false;domStorageEnabled=false
            allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(false)
            blockNetworkLoads=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture=true;setGeolocationEnabled(false)
            safeBrowsingEnabled=true;cacheMode=WebSettings.LOAD_NO_CACHE
            useWideViewPort=true;loadWithOverviewMode=true
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
            override fun onScaleChanged(view: WebView,oldScale: Float,newScale: Float){renderScale=newScale;publishHeight()}
            override fun onPageFinished(view: WebView,url: String){if(live)postVisualStateCallback(1,object: WebView.VisualStateCallback(){override fun onComplete(requestId: Long){if(live){publishHeight();ready()}}})}
        }
    }
    fun show(next: MailHtmlDocument,fontScale: Float=1f) {
        val zoom=(fontScale*100).toInt().coerceIn(50,300)
        if(settings.textZoom!=zoom)settings.textZoom=zoom
        if(document==next)return
        document=next
        loadDataWithBaseURL(HtmlMail.ORIGIN,next.html,"text/html","UTF-8",null)
    }
    override fun onDraw(canvas: Canvas){super.onDraw(canvas);publishHeight()}
    private fun publishHeight() {
        if(!live)return
        val content=contentHeight
        val value=ceil(content*renderScale).toInt().coerceIn(1,500_000)
        if(value==lastHeight || content<=0)return
        lastHeight=value
        post{if(live)heightChanged(value)}
    }
    fun release(){live=false;stopLoading();document=null;destroy()}
}

@Composable fun MailHtmlBody(message: MailItem,plain: String,hasHistory: Boolean,onInline: suspend (Attachment)->OpenedAttachment,onLink: (String)->Unit) {
    val context=LocalContext.current;val density=LocalDensity.current
    var external by rememberSaveable(message.id){mutableStateOf(false)}
    var height by remember(message.id){mutableIntStateOf(8)}
    // Rich mail retains a light default canvas; sender-defined dark tables keep their own colors.
    val foreground=if(message.htmlBody.isNotBlank())"#20241f"else "#%06x".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff)
    val background=if(message.htmlBody.isNotBlank())"#f8f9f6"else "#%06x".format(MaterialTheme.colorScheme.surface.toArgb() and 0xffffff)
    val document by produceState<MailHtmlDocument?>(null,message.id,message.htmlBody,message.attachments,message.contentVersion,message.accountId,message.validity,plain,hasHistory,external,foreground,background) {
        value=withContext(Dispatchers.Default){HtmlMail.document(message.htmlBody,plain,message.attachments,hasHistory,external,foreground,background,identity="${message.accountId}:${message.id}:${message.validity}")}
    }
    val latestInline by rememberUpdatedState(onInline)
    val latestLink by rememberUpdatedState(onLink)
    val loader=remember(message.id,message.contentVersion){MailImageLoader(context.applicationContext,message.accountId){latestInline(it)}}
    DisposableEffect(loader){onDispose{loader.close()}}
    val ready=document
    if(ready==null)Text("Готовим письмо…",Modifier.padding(vertical=14.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        if(ready.externalImages)TextButton(onClick={external=!external},modifier=Modifier.fillMaxWidth()) {
            Text(if(external)"Скрыть внешние изображения" else "Показать изображения")
        }
        key(message.id,message.contentVersion) {
            AndroidView(factory={c->MailHtmlWebView(c,loader::load,{latestLink(it)},{height=it})},
                modifier=Modifier.fillMaxWidth().padding(vertical=10.dp).height(with(density){height.toDp()}),
                update={it.show(ready,density.fontScale)},onRelease={it.release()})
        }
    }
}
