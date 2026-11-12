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
    private var view: ComposeEditorWebView?=null
    fun accept(id: String,html: String){value.set(Value(id,html))}
    fun snapshot(draft: ComposeDraft): ComposeDraft = value.get()?.takeIf{it.id==draft.id}?.let{ComposerHtml.replace(draft,it.html)}?:draft
    fun insertText(text: String){view?.insertHtml(ComposeContent.escaped(text).replace("\n","<br>"))}
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
    private val session: ComposeEditorSession,private val changed: (String)->Unit,private val resized: (Int)->Unit): WebView(context) {
    private var editingEnabled=true
    var ready=false;private set
    private var alive=true
    init {
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
                session.accept(draftId,safe)
                post{if(alive)changed(safe)}
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
        val csp="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data:; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
        loadDataWithBaseURL("https://compose.liftzip.invalid/", """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$csp"><style>
          html,body{margin:0;padding:0;background:white;color:#303030;font:16px/1.5 sans-serif}
          #editor{box-sizing:border-box;min-height:280px;padding:16px;outline:none;overflow-wrap:anywhere}
          #editor:empty:before{content:'Текст письма';color:#777;pointer-events:none}
          #editor img{max-width:100%;height:auto} #editor table{max-width:100%} #editor pre{white-space:pre-wrap}
        </style></head><body><div id="editor" role="textbox" aria-label="Текст письма" aria-multiline="true" contenteditable="true">${ComposerHtml.clean(initial)}</div><script nonce="$nonce">
          (function(){
            const editor=document.getElementById('editor');let range=null;
            const emit=()=>EditorBridge.input(editor.innerHTML);
            document.addEventListener('selectionchange',()=>{const s=getSelection();if(s.rangeCount && editor.contains(s.anchorNode))range=s.getRangeAt(0).cloneRange();});
            editor.addEventListener('input',emit);
            editor.addEventListener('paste',e=>{e.preventDefault();EditorBridge.paste(e.clipboardData.getData('text/html'),e.clipboardData.getData('text/plain'));});
            editor.addEventListener('drop',e=>e.preventDefault());
            document.addEventListener('click',e=>{if(e.target.closest('a'))e.preventDefault();});
            window.LiftZipEditor={
              html:()=>editor.innerHTML,
              enabled:value=>editor.contentEditable=String(value),
              insert:html=>{editor.focus();const s=getSelection();s.removeAllRanges();if(range && editor.contains(range.commonAncestorContainer))s.addRange(range);else{const r=document.createRange();r.selectNodeContents(editor);r.collapse(false);s.addRange(r);}document.execCommand('insertHTML',false,html);emit();},
              selectAll:()=>{editor.focus();const r=document.createRange();r.selectNodeContents(editor);const s=getSelection();s.removeAllRanges();s.addRange(r);range=r;}
            };
            new ResizeObserver(()=>EditorBridge.height(Math.ceil(editor.scrollHeight))).observe(editor);
          })();
        </script></body></html>""", "text/html","UTF-8",null)
    }
    fun setEditable(value: Boolean){if(value!=editingEnabled){editingEnabled=value;if(ready)applyEditable()}}
    private fun applyEditable(){evaluateJavascript("window.LiftZipEditor.enabled($editingEnabled)",null)}
    fun insertHtml(html: String){if(ready)evaluateJavascript("window.LiftZipEditor.insert(${JSONObject.quote(ComposerHtml.clean(html))})",null)}
    fun pasteClipboard(plain: Boolean=false): Boolean {
        val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?:return false
        val fragments=(0 until clip.itemCount).map { index->
            val item=clip.getItemAt(index)
            if(!plain && !item.htmlText.isNullOrBlank())ComposerHtml.clean(item.htmlText!!)
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
    var height by remember(draft.id){mutableIntStateOf(280)}
    key(draft.id) {
        AndroidView(factory={context->ComposeEditorWebView(context,draft.id,ComposerHtml.initial(draft),session,{html->
            if(current.id==draft.id)change(ComposerHtml.replace(current,html))
        },{height=it}).also(session::attach)},
            update={view->view.setEditable(enabled)},
            onRelease={it.close()},
            modifier=Modifier.fillMaxWidth().height(height.coerceIn(280,4000).dp).testTag("compose-body").semantics {
                editableText=AnnotatedString(draft.body)
                // Accessibility/test input uses the same DOM editing path as native IME input.
                insertTextAtCursor{value->session.insertText(value.text);true}
            })
    }
}
