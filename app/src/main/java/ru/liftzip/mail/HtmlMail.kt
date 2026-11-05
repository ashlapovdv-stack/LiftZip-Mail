package ru.liftzip.mail

import java.net.URI
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.jsoup.nodes.Node

sealed interface MailImageSource {
    data class Inline(val attachment: Attachment): MailImageSource
    data class Remote(val url: String): MailImageSource
    data class Data(val mime: String,val base64: String): MailImageSource
}
data class MailHtmlDocument(val html: String,val images: Map<String,MailImageSource>,val externalImages: Boolean)

/** HTML remains data. Only the renderer's generated image URLs can request resources. */
object HtmlMail {
    const val ORIGIN="https://mail.liftzip.invalid/"
    private val links=Regex("""(?i)\b(?:https?://[^\s<>"']+|mailto:[^\s<>"']+|www\.[^\s<>"']+|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,})""")
    private val unsafe="script,iframe,frame,frameset,object,embed,applet,form,input,button,select,textarea,link,base,meta,svg,math,video,audio,source,track,canvas,noscript,template"
    private fun safelist()=Safelist.relaxed().addTags("style","section","article","header","footer","main","hr","s","strike","center","font").addAttributes("font","color","face","size")
        .addAttributes(":all","style","class","id","dir","align","valign","width","height","bgcolor","border","cellpadding","cellspacing","role")
        .addProtocols("img","src","cid","data").removeProtocols("a","href","ftp")
    fun link(raw: String): String? {
        val value=raw.trim()
        if(value.length>8192 || value.any{it.code<32 || it=='\\'})return null
        val u=runCatching{URI(value)}.getOrNull()?:return null
        return when(u.scheme?.lowercase()) {
            "http","https"->value.takeIf{!u.rawAuthority.isNullOrBlank() && u.rawUserInfo==null && !u.rawAuthority.contains('@')}
            "mailto"->value.takeIf{u.rawSchemeSpecificPart.isNotBlank() && !value.contains(Regex("(?i)%0[ad]"))}
            else->null
        }
    }
    fun imageUrl(raw: String): String? {
        val valid=link(raw)?:return null
        val u=URI(valid)
        if(u.scheme.equals("mailto",true))return null
        return if(u.scheme.equals("http",true))"https"+valid.substring(valid.indexOf(':')) else valid
    }
    fun cid(raw: String)=runCatching{URLDecoder.decode(raw.replace("+","%2B"),"UTF-8")}.getOrDefault(raw).trim().removePrefix("<").removeSuffix(">")
    fun cleanSource(source: String): String {
        val doc=Jsoup.parse(source)
        doc.select(unsafe).remove()
        // Email styles commonly live in head. Keep them for layout, remove them from plain text.
        doc.head().select("style").toList().asReversed().forEach{doc.body().prependChild(it)}
        val body=doc.body()
        if(body.attributes().size()>0) {
            val wrapper=Element("div")
            body.attributes().forEach{wrapper.attr(it.key,it.value)}
            val color=body.attr("bgcolor")
            if(color.matches(Regex("#[a-fA-F0-9]{3,8}|[a-zA-Z]{1,24}")))wrapper.attr("style",wrapper.attr("style")+";background-color:$color")
            body.childNodes().toList().forEach{wrapper.appendChild(it)}
            body.appendChild(wrapper)
        }
        val safe=Cleaner(safelist()).clean(doc)
        safe.outputSettings().prettyPrint(false)
        safe.select("a").forEach{a->val url=link(a.attr("href"));if(url==null)a.removeAttr("href")else a.attr("href",url)}
        return safe.body().html()
    }
    fun text(source: String): String {
        val doc=Jsoup.parse(source)
        doc.select("head,style,$unsafe,[hidden]").remove()
        doc.select("[style]").filter{it.attr("style").contains(Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)"))}.forEach{it.remove()}
        doc.select("a[href]").forEach{a->link(a.attr("href"))?.let{url->if(a.text().trim()!=url)a.appendText(" ($url)")}}
        val out=StringBuilder()
        fun newline(){if(out.isNotEmpty() && out.last()!='\n')out.append('\n')}
        NodeTraversor.traverse(object: NodeVisitor {
            override fun head(node: Node,depth: Int) {
                when(node) {
                    is TextNode->out.append(node.text())
                    is Element->if(node.normalName() in setOf("br","p","div","tr","li","blockquote","h1","h2","h3","h4","h5","h6","hr"))newline()
                }
            }
            override fun tail(node: Node,depth: Int) {if(node is Element && node.isBlock)newline()}
        },doc.body())
        return out.toString().replace('\u00a0',' ').replace("\uFFFC","").replace(Regex("[ \\t]+\\n"),"\n").replace(Regex("\\n{3,}"),"\n\n").trim()
    }
    fun plain(source: String): String {
        val out=StringBuilder();var end=0
        for(match in links.findAll(source)) {
            var raw=match.value.trimEnd('.',',',';','!','?')
            for((open,close) in listOf('(' to ')','[' to ']'))while(raw.endsWith(close) && raw.count{it==close}>raw.count{it==open})raw=raw.dropLast(1)
            val target=when{raw.startsWith("www.",true)->"https://$raw";!raw.contains(":") && raw.contains("@")->"mailto:$raw";else->raw}
            val url=link(target)?:continue
            out.append(escape(source.substring(end,match.range.first)))
            out.append("<a href=\"").append(escape(url)).append("\">").append(escape(raw)).append("</a>")
            end=match.range.first+raw.length
        }
        out.append(escape(source.substring(end)))
        return "<div class=\"mail-plain\">$out</div>"
    }
    fun height(content: Int,scale: Float): Int {
        val factor=scale.takeIf{it.isFinite() && it>0f}?:1f
        // Rounding down prevents viewport minimum-height rounding feeding back into endless growth.
        return (content.toDouble()*factor).toInt().coerceIn(1,500_000)
    }
    private fun escape(value: String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun currentSource(html: String,hasHistory: Boolean): String? {
        if(!hasHistory)return html
        val doc=Jsoup.parseBodyFragment(html)
        val quotes=doc.select("blockquote,.gmail_quote,.yahoo_quoted,.protonmail_quote,[type=cite]")
        if(quotes.isEmpty())return null // Preserve uncertain reply boundaries using the parsed plain-text history.
        quotes.remove()
        doc.select(".gmail_attr").remove()
        return doc.body().html()
    }
    fun document(html: String,plain: String,attachments: List<Attachment>,hasHistory: Boolean=false,external: Boolean=false,
        foreground: String="#20241f",background: String="#f8f9f6",identity: String=""): MailHtmlDocument {
        val source=html.takeIf{it.isNotBlank()}?.let{currentSource(it,hasHistory)}?:this.plain(plain)
        val namespace=MailImageLimits.hash(identity+source+external+attachments.joinToString{it.path+it.contentId})
        val doc=Jsoup.parseBodyFragment(cleanSource(source))
        val resources=linkedMapOf<String,MailImageSource>();var hasExternal=false
        fun resource(raw: String): Pair<String,MailImageSource>? {
            val image=when {
                raw.startsWith("cid:",true)->attachments.firstOrNull{cid(it.contentId)==cid(raw.substring(4)) && it.contentId.isNotBlank() && it.mime.startsWith("image/",true) }?.let{MailImageSource.Inline(it)}
                raw.startsWith("data:",true)->Regex("(?is)^data:(image/(?:png|jpeg|gif|webp));base64,([a-z0-9+/=\\s]+)$").matchEntire(raw)?.takeIf{raw.length<2*1024*1024}?.let{MailImageSource.Data(it.groupValues[1].lowercase(),it.groupValues[2])}
                else->imageUrl(raw)?.let{MailImageSource.Remote(it)}
            }?:return null
            if(image is MailImageSource.Remote)hasExternal=true
            if(image is MailImageSource.Remote && !external)return null
            if(resources.size>=64)return null
            val old=resources.entries.firstOrNull{it.value==image}
            val path=old?.key?:"${ORIGIN}image/$namespace/${resources.size}".also{resources[it]=image}
            return path to image
        }
        doc.select("img").toList().forEach{img->
            if(img.attr("width").toIntOrNull()?.let{it<=2}==true && img.attr("height").toIntOrNull()?.let{it<=2}==true){img.remove();return@forEach}
            val resolved=resource(img.attr("src"))
            if(resolved==null)img.replaceWith(Element("span").addClass("mail-image-placeholder").text(img.attr("alt").ifBlank{"Изображение"}))
            else{img.attr("src",resolved.first);img.removeAttr("srcset")}
        }
        // CSS images use the same isolated loader; external fonts and unknown resources stay blocked.
        val cssUrls=Regex("(?i)url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)")
        fun css(value: String)=cssUrls.replace(value){m->resource(m.groupValues[1].trim())?.let{"url('${it.first}')"}?:"none"}
        doc.select("[style]").forEach{it.attr("style",css(it.attr("style")))}
        doc.select("style").forEach{it.html(css(it.data()))}
        doc.select("a").forEach{it.removeAttr("target");it.attr("rel","noreferrer noopener")}
        val csp="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src $ORIGIN; font-src 'none'; media-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; connect-src 'none'"
        val wrapped="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$csp"><meta name="referrer" content="no-referrer"><style>
            html,body{margin:0;padding:0;max-width:100%;overflow-wrap:anywhere;background:$background;color:$foreground;font:16px/1.55 sans-serif}
            table{max-width:100%!important} img{max-width:100%!important;height:auto} a{color:#526b45;text-decoration:underline}
            .mail-plain{white-space:pre-wrap}.mail-image-placeholder{display:inline-block;padding:8px 12px;margin:4px 0;border:1px solid #b9c3b4;border-radius:10px;font:13px/1.4 sans-serif}
            </style></head><body>${doc.body().html()}</body></html>""".trimIndent()
        return MailHtmlDocument(wrapped,resources,hasExternal)
    }
}
