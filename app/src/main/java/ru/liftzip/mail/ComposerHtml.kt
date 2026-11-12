package ru.liftzip.mail

import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** Editable author HTML, never a reader document. CSS is local presentation only. */
object ComposerHtml {
    private val tags="p,div,span,br,b,strong,i,em,u,s,strike,sub,sup,font,h1,h2,h3,h4,h5,h6,ul,ol,li,blockquote,pre,code,hr,table,thead,tbody,tfoot,tr,td,th,caption,colgroup,col,a,img,center"
    private val safe=Safelist.none().addTags(*tags.split(',').toTypedArray())
        .addAttributes(":all","style","dir","align")
        .addAttributes("a","href","title").addProtocols("a","href","http","https","mailto")
        .addAttributes("img","src","alt","width","height").addProtocols("img","src","http","https","cid","data")
        .addAttributes("font","color","face","size").addAttributes("ol","start","type").addAttributes("li","value")
        .addAttributes("blockquote","type","cite")
        .addAttributes("table","border","cellpadding","cellspacing","width","bgcolor")
        .addAttributes("td","colspan","rowspan","width","height","valign","bgcolor")
        .addAttributes("th","colspan","rowspan","width","height","valign","bgcolor")
        .addAttributes("col","span","width")
    private val properties=setOf("color","background-color","font-family","font-size","font-weight","font-style","text-decoration","text-align","vertical-align","white-space","line-height","letter-spacing","word-spacing","border","border-width","border-style","border-color","border-collapse","border-spacing","padding","padding-left","padding-right","padding-top","padding-bottom","margin","margin-left","margin-right","margin-top","margin-bottom","width","height","max-width","min-width","list-style-type")
    private fun css(source: String): String = source.split(';').mapNotNull { declaration->
        val pair=declaration.split(':',limit=2)
        if(pair.size!=2)return@mapNotNull null
        val name=pair[0].trim().lowercase();val value=pair[1].trim()
        val property=if(name=="background")"background-color"else name
        if(property !in properties || value.length>256 || !value.matches(Regex("[a-zA-Z0-9#(),.%'\" _+\\-]+")) ||
            Regex("(?i)(url|expression|javascript|behavior|import|binding)\\s*\\(?").containsMatchIn(value))null else "$property:$value"
    }.joinToString(";")
    fun clean(source: String): String {
        val doc=Jsoup.parse(source)
        doc.outputSettings().prettyPrint(false)
        doc.select("script,iframe,object,embed,svg,math,template,noscript,form,input,button,textarea,select,meta,link,base").remove()
        // Clipboard HTML sometimes uses classes plus a stylesheet. Resolve simple rules
        // inside the fragment, then discard all selectors before loading the editor.
        doc.select("style").forEach { style->
            Regex("([^{}]+)\\{([^{}]*)}").findAll(style.data()).forEach { rule->
                val selector=rule.groupValues[1].trim();val declarations=css(rule.groupValues[2])
                if(!selector.contains('@') && declarations.isNotBlank())runCatching {
                    doc.select(selector).forEach{node->node.attr("style",declarations+";"+node.attr("style"))}
                }
            }
        }
        doc.select("style").remove()
        val cleaned=Cleaner(safe).clean(doc)
        cleaned.outputSettings().prettyPrint(false)
        cleaned.select("[style]").forEach{node->val value=css(node.attr("style"));if(value.isBlank())node.removeAttr("style")else node.attr("style",value)}
        cleaned.select("a[href]").forEach{if(HtmlMail.link(it.attr("href"))==null)it.removeAttr("href")}
        cleaned.select("img[src]").forEach { image->
            val src=image.attr("src")
            val allowed=src.startsWith("cid:",true) || HtmlMail.imageUrl(src)!=null ||
                src.matches(Regex("(?i)data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=\\r\\n]+"))
            if(!allowed)image.removeAttr("src")
        }
        return cleaned.body().html()
    }
    fun plain(html: String)=HtmlMail.text(clean(html))
    fun hasContent(draft: ComposeDraft)=draft.bodyHtml?.let{html->plain(html).isNotBlank() || Jsoup.parse(clean(html)).select("img[src]").isNotEmpty()}?:draft.body.isNotBlank()
    fun initial(draft: ComposeDraft)=draft.bodyHtml?.let(::clean)?:ComposeContent.escaped(draft.body).replace("\n","<br>")
    fun forEditing(draft: ComposeDraft): ComposeDraft {
        val legacy=draft.quote?.takeIf{it.kind=="draft" && draft.quoteIncluded && draft.bodyHtml==null}?:return draft
        val author=listOf(initial(draft),legacy.html.ifBlank{ComposeContent.escaped(legacy.plain).replace("\n","<br>")}).filter{it.isNotBlank()}.joinToString("<br><br>")
        return replace(draft.copy(quote=legacy.copy(plain="",html="")),author)
    }
    fun replace(draft: ComposeDraft,html: String): ComposeDraft {
        val safe=clean(html)
        return draft.copy(bodyHtml=safe,body=HtmlMail.text(safe))
    }
}
