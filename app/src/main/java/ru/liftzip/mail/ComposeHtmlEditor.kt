package ru.liftzip.mail

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Latest input survives a pending Compose recomposition and the file-backed lifecycle saver.
 * The DOM owns the active edit; persistence/transport receive an immutable snapshot. */
internal class ComposeEditorSession {
    private data class Value(val id: String,val html: String)
    private val value=AtomicReference<Value?>(null)
    @Volatile private var view: ComposeEditorWebView?=null
    fun input(editor: ComposeEditorWebView,html: String){if(view===editor)accept(editor.draftId,html)}
    fun accept(id: String,html: String){value.set(Value(id,html))}
    fun snapshot(draft: ComposeDraft): ComposeDraft = value.get()?.takeIf{it.id==draft.id}?.let{ComposerHtml.replace(draft,it.html)}?:draft
    fun focus(){view?.let{it.requestFocus();it.evaluateJavascript("document.getElementById('editor').focus()",null)}}
    fun replaceText(text: String){view?.let{editor->if(editor.ready)editor.evaluateJavascript("window.LiftZipEditor.selectAll();window.LiftZipEditor.insert("+JSONObject.quote(ComposeContent.escaped(text).replace("\n","<br>"))+")",null)}}
    fun insertText(text: String){view?.insertHtml(ComposeContent.escaped(text).replace("\n","<br>"))}
    fun appendImage(dataUri: String,cid: String){view?.appendImage(dataUri,cid)}
    fun attach(editor: ComposeEditorWebView){view=editor}
    fun detach(editor: ComposeEditorWebView){if(view===editor)view=null}
    fun flush(draft: ComposeDraft,done: (ComposeDraft)->Unit) {
        val editor=view?.takeIf{it.draftId==draft.id && it.ready}
        if(editor==null){done(snapshot(draft));return}
        editor.evaluateJavascript("window.LiftZipEditor.html()") { result->
            val html=runCatching{JSONObject("{\"html\":$result}").getString("html")}.getOrNull()
            if(html!=null)accept(draft.id,html)
            done(snapshot(draft))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class ComposeEditorWebView(context: Context,val draftId: String,initial: String,
    private val session: ComposeEditorSession,private val changed: (String,String)->Unit,private val resized: (Int)->Unit,
    private val previews: Map<String,String> = emptyMap()): WebView(context) {
    private var editingEnabled=true
    var ready=false;private set
    @Volatile private var alive=true
    init {
        session.accept(draftId,initial)
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled=true
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.blockNetworkLoads=true;settings.domStorageEnabled=false
        settings.cacheMode=WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        isVerticalScrollBarEnabled=false
        addJavascriptInterface(object {
            @JavascriptInterface fun input(html: String) {
                if(!alive)return
                val safe=ComposerHtml.clean(html)
                val plain=ComposerHtml.plain(safe)
                session.input(this@ComposeEditorWebView,safe)
                post{if(alive)changed(safe,plain)}
            }
            @JavascriptInterface fun height(pixels: Int){post{if(alive)resized(pixels.coerceAtLeast(280))}}
            @JavascriptInterface fun paste(html: String,text: String){post{if(alive)insertHtml(if(html.isNotEmpty())ComposerHtml.clean(html)else ComposeContent.escaped(text).replace("\n","<br>"))}}
        },"EditorBridge")
        webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)=true
            override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest)=WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view: WebView,url: String){ready=true;applyEditable()}
        }
        val nonce=UUID.randomUUID().toString()
        val previewHtml=org.jsoup.Jsoup.parseBodyFragment(ComposerHtml.clean(initial)).also { doc->
            doc.select("img[src^=cid:]").forEach { img->
                val cid=img.attr("src").substring(4)
                previews[cid]?.let { data->img.attr("data-liftzip-cid",cid);img.attr("src",data) }
            }
        }.body().html()
        val csp="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data:; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
        loadDataWithBaseURL("https://compose.liftzip.invalid/", """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$csp"><style>
          html,body{margin:0;padding:0;background:white;color:#303030;font:16px/1.5 sans-serif}
          #editor{box-sizing:border-box;min-height:280px;padding:16px;outline:none;overflow-wrap:anywhere}
          #editor:empty:before{content:'Текст письма';color:#777;pointer-events:none}
          #editor img{max-width:100%;height:auto} #editor table{max-width:100%!important}
          #editor p,#editor div,#editor span,#editor a,#editor td,#editor th{white-space:normal!important;overflow-wrap:anywhere}
          #editor pre,#editor pre *{white-space:pre-wrap!important;overflow-wrap:anywhere}
        </style></head><body><div id="editor" role="textbox" aria-label="Текст письма" aria-multiline="true" contenteditable="true">${previewHtml}</div><script nonce="$nonce">
          (function(){
            const editor=document.getElementById('editor');let range=null;
            const serialized=()=>{const copy=editor.cloneNode(true);copy.querySelectorAll('img[data-liftzip-cid]').forEach(img=>{img.setAttribute('src','cid:'+img.getAttribute('data-liftzip-cid'));img.removeAttribute('data-liftzip-cid');});return copy.innerHTML;};
            const emit=()=>EditorBridge.input(serialized());
            document.addEventListener('selectionchange',()=>{const s=getSelection();if(s.rangeCount && editor.contains(s.anchorNode))range=s.getRangeAt(0).cloneRange();});
            editor.addEventListener('input',emit);
            editor.addEventListener('paste',e=>{e.preventDefault();EditorBridge.paste(e.clipboardData.getData('text/html'),e.clipboardData.getData('text/plain'));});
            editor.addEventListener('drop',e=>e.preventDefault());
            document.addEventListener('click',e=>{if(e.target.closest('a'))e.preventDefault();});
            window.LiftZipEditor={
              html:()=>serialized(),
              enabled:value=>editor.contentEditable=String(value),
              insert:html=>{const saved=range&&range.cloneRange();editor.focus();const s=getSelection();s.removeAllRanges();if(saved && editor.contains(saved.commonAncestorContainer))s.addRange(saved);else{const r=document.createRange();r.selectNodeContents(editor);r.collapse(false);s.addRange(r);}const r=s.getRangeAt(0);r.deleteContents();const fragment=r.createContextualFragment(html);const last=fragment.lastChild;r.insertNode(fragment);if(last)r.setStartAfter(last);r.collapse(true);s.removeAllRanges();s.addRange(r);range=r.cloneRange();editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste'}));},
              appendImage:(src,cid)=>{const img=document.createElement("img");img.src=src;img.setAttribute('data-liftzip-cid',cid);img.alt="Фото";editor.appendChild(document.createElement("br"));editor.appendChild(img);editor.appendChild(document.createElement("br"));editor.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"insertFromPaste"}));},
              selectAll:()=>{editor.focus();const r=document.createRange();r.selectNodeContents(editor);const s=getSelection();s.removeAllRanges();s.addRange(r);range=r;}
            };
            new ResizeObserver(()=>EditorBridge.height(Math.ceil(editor.scrollHeight))).observe(editor);
          })();
        </script></body></html>""", "text/html","UTF-8",null)
    }
    fun setEditable(value: Boolean){if(value!=editingEnabled){editingEnabled=value;if(ready)applyEditable()}}
    private fun applyEditable(){evaluateJavascript("window.LiftZipEditor.enabled($editingEnabled)",null)}
    fun appendImage(dataUri: String,cid: String){if(ready)evaluateJavascript("window.LiftZipEditor.appendImage(${JSONObject.quote(dataUri)},${JSONObject.quote(cid)})",null)}
    fun insertHtml(html: String){if(ready)evaluateJavascript("window.LiftZipEditor.insert(${JSONObject.quote(ComposerHtml.clean(html))})",null)}
    fun pasteClipboard(plain: Boolean=false): Boolean {
        val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?:return false
        val fragments=(0 until clip.itemCount).map { index->
            val item=clip.getItemAt(index)
            if(!plain && !item.htmlText.isNullOrBlank())ComposerHtml.clean(item.htmlText!!)
            else if(!plain && item.text is android.text.Spanned)ComposerHtml.clean(android.text.Html.toHtml(item.text as android.text.Spanned,android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE))
            else ComposeContent.escaped(item.coerceToText(context).toString()).replace("\n","<br>")
        }
        insertHtml(fragments.joinToString("<br>"));return true
    }
    private fun wrap(callback: ActionMode.Callback)=object: ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode,menu: Menu)=callback.onCreateActionMode(mode,menu)
        override fun onPrepareActionMode(mode: ActionMode,menu: Menu)=callback.onPrepareActionMode(mode,menu)
        override fun onDestroyActionMode(mode: ActionMode)=callback.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode,view: View,out: android.graphics.Rect){if(callback is ActionMode.Callback2)callback.onGetContentRect(mode,view,out)else super.onGetContentRect(mode,view,out)}
        override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean {
            if(item.itemId==android.R.id.paste || item.itemId==android.R.id.pasteAsPlainText) {
                val pasted=pasteClipboard(item.itemId==android.R.id.pasteAsPlainText)
                if(pasted)mode.finish()
                return pasted
            }
            return callback.onActionItemClicked(mode,item)
        }
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode?=super.startActionMode(wrap(callback))
    override fun startActionMode(callback: ActionMode.Callback,type: Int): ActionMode?=super.startActionMode(wrap(callback),type)
    override fun onKeyShortcut(keyCode: Int,event: KeyEvent): Boolean =
        if(keyCode==KeyEvent.KEYCODE_V && event.isCtrlPressed)pasteClipboard(event.isShiftPressed)else super.onKeyShortcut(keyCode,event)
    override fun performAccessibilityAction(action: Int,arguments: Bundle?): Boolean =
        if(action==AccessibilityNodeInfo.ACTION_PASTE)pasteClipboard()else super.performAccessibilityAction(action,arguments)
    fun close(){alive=false;ready=false;session.detach(this);removeJavascriptInterface("EditorBridge");stopLoading();destroy()}
}

@Composable internal fun ComposeHtmlEditor(draft: ComposeDraft,enabled: Boolean,session: ComposeEditorSession,onChange: (ComposeDraft)->Unit) {
    val current by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    var editorFocused by remember(draft.id){mutableStateOf(false)}
    var height by remember(draft.id){mutableIntStateOf(280)}
    key(draft.id) {
        AndroidView(factory={context->ComposeEditorWebView(context,draft.id,ComposerHtml.initial(draft),session,{html,plain->
            if(current.id==draft.id)change(current.copy(bodyHtml=html,body=plain))
        },{height=it},draft.filesToSend().filter{it.contentId.isNotBlank()}.mapNotNull { file->
                runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(file.uri))?.use { input->
                    val bytes=input.readBytes();if(bytes.size<=1_000_000)file.contentId to ("data:${file.mime};base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)) else null
                }}.getOrNull()
            }.filterNotNull().toMap()).also{view->session.attach(view);view.onFocusChangeListener=View.OnFocusChangeListener{_,focused->editorFocused=focused}}},
            update={view->view.setEditable(enabled)},
            onRelease={it.close()},
            modifier=Modifier.fillMaxWidth().height(height.coerceIn(280,4000).dp).testTag("compose-body").semantics {
                editableText=AnnotatedString(draft.body)
                focused=editorFocused
                if(!enabled)disabled()
                setText{value->session.replaceText(value.text);true}
                requestFocus{session.focus();true}
                // Accessibility/test input uses the same DOM editing path as native IME input.
                insertTextAtCursor{value->session.insertText(value.text);true}
            })
    }
}
