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
data class MailHtmlDocument(val html: String,val images: Map<String,MailImageSource>,val externalImages: Boolean,val layoutWidth: Int=0,val externalAllowed: Boolean=false,val originalScale: Boolean=false)

/** HTML remains data. Only the renderer's generated image URLs can request resources. */
object HtmlMail {
    const val ORIGIN="https://mail.liftzip.invalid/"
    private val links=Regex("""(?i)\b(?:https?://[^\s<>"']+|mailto:[^\s<>"']+|www\.[^\s<>"']+|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,})""")
    private val unsafe="script,iframe,frame,frameset,object,embed,applet,form,input,button,select,textarea,link,base,meta,svg,math,video,audio,source,track,canvas,noscript,template"
    private val pixelWidth=Regex("(?i)(?:^|[;{])\\s*(?:min-)?width\\s*:\\s*(\\d{2,4})px(?:\\s*!important)?\\s*(?=[;}])")
    private val neutralBackground=Regex("(?i)(background(?:-color)?\\s*:\\s*)(#[0-9a-f]{3,8}|rgba?\\([^)]*\\)|white|whitesmoke)(\\s*!important)?(?=\\s*[;}])")
    /** Remove only near-white neutral fills. Coloured highlighting and dark panels stay intact. */
    internal fun neutralFill(value: String): Boolean {
        val v=value.trim().lowercase()
        if(v in setOf("white","whitesmoke"))return true
        val rgb=when {
            v.matches(Regex("#[0-9a-f]{3}"))->v.drop(1).map{it.digitToInt(16)*17}
            v.matches(Regex("#[0-9a-f]{6}"))->v.drop(1).chunked(2).map{it.toInt(16)}
            v.matches(Regex("rgb\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*\\)"))->Regex("\\d+").findAll(v).map{it.value.toInt()}.toList()
            else->return false
        }
        return rgb.size==3 && rgb.all{it in 235..255} && (rgb.max()-rgb.min())<=20
    }
    internal fun transparentNeutralBackgrounds(css: String): String=neutralBackground.replace(css+";"){match->
        if(neutralFill(match.groupValues[2]))match.groupValues[1]+"transparent"+match.groupValues[3] else match.value
    }.dropLast(1)
    /** Give desktop tables their natural layout space, then let WebView fit that space to the screen. */
    fun layoutWidth(source: String): Int=layoutWidth(Jsoup.parse(source))
    private fun layoutWidth(doc: org.jsoup.nodes.Document): Int {
        fun width(element: Element): Int {
            val attribute=element.attr("width").trim().takeIf{it.matches(Regex("\\d{2,4}"))}?.toIntOrNull()?:0
            val inline=pixelWidth.findAll(element.attr("style")+";").maxOfOrNull{it.groupValues[1].toInt()}?:0
            return maxOf(attribute,inline)
        }
        var result=doc.select("table,div,section,article,main,body").maxOfOrNull(::width)?:0
        doc.select("tr").forEach{row->
            val cells=row.children().filter{it.normalName() in setOf("td","th")}
            // Only the row's own fixed cell widths demand layout space. The bare number
            // of columns says nothing about how wide an adaptive table actually is, so
            // it must never force a desktop viewport (and the matching text shrink).
            result=maxOf(result,cells.sumOf(::width))
        }
        doc.select("style").forEach{style->result=maxOf(result,pixelWidth.findAll(withoutMediaBlocks(style.data())).maxOfOrNull{it.groupValues[1].toInt()}?:0)}
        return result.takeIf{it>=480}?.coerceAtMost(1440)?:0
    }
    /** Width rules inside conditional (@media) blocks target other viewports. Applied
     *  unconditionally they inflate the layout viewport and shrink an adaptive letter. */
    internal fun withoutMediaBlocks(css: String): String {
        val out=StringBuilder();var index=0
        while(index<css.length) {
            if(css[index]=='@') {
                var probe=index+1
                while(probe<css.length && (css[probe].isLetterOrDigit()||css[probe]=='-'))probe++
                val rule=css.substring(index+1,probe).lowercase()
                var cursor=probe
                while(cursor<css.length && css[cursor]!=';' && css[cursor]!='{')cursor++
                if(rule=="media" && cursor<css.length && css[cursor]=='{') {
                    var depth=1;cursor++
                    while(cursor<css.length && depth>0) {
                        when(css[cursor]){'{'->depth++;'}'->depth--;else->Unit}
                        cursor++
                    }
                    index=cursor;continue
                }
            }
            out.append(css[index]);index++
        }
        return out.toString()
    }
    private fun safelist()=Safelist.relaxed().addTags("style","section","article","header","footer","main","hr","s","strike","center","font").addAttributes("font","color","face","size")
        .addAttributes(":all","style","class","id","dir","align","valign","width","height","bgcolor","border","cellpadding","cellspacing","role","aria-label","aria-expanded")
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
    fun text(source: String)=text(source,true)
    private fun text(source: String,includeLinkTargets: Boolean): String {
        val doc=Jsoup.parse(source)
        doc.select("head,style,$unsafe,[hidden]").remove()
        doc.select("[style]").filter{it.attr("style").contains(Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)"))}.forEach{it.remove()}
        if(includeLinkTargets)doc.select("a[href]").forEach{a->link(a.attr("href"))?.let{url->if(a.text().trim()!=url)a.appendText(" ($url)")}}
        val out=StringBuilder()
        fun newline(){if(out.isNotEmpty() && out.last()!='\n')out.append('\n')}
        NodeTraversor.traverse(object: NodeVisitor {
            override fun head(node: Node,depth: Int) {
                when(node) {
                    is TextNode->out.append(node.text())
                    is Element->if(node.normalName() in setOf("td","th"))out.append(' ')else if(node.normalName() in setOf("br","p","div","tr","li","blockquote","h1","h2","h3","h4","h5","h6","hr"))newline()
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
    const val MAX_VIEWPORT_PIXELS=8192
    fun height(content: Int,scale: Float,viewport: Int=MAX_VIEWPORT_PIXELS): Int {
        val factor=scale.takeIf{it.isFinite() && it>0f}?:1f
        // Rounding down prevents viewport minimum-height rounding feeding back into endless growth.
        // A WebView is a viewport, not a bitmap as tall as the entire email. Compose packs
        // dimensions into Constraints; even valid long mail can exceed that representation.
        return (content.toDouble()*factor).toInt().coerceIn(1,viewport.coerceIn(1,MAX_VIEWPORT_PIXELS))
    }
    private fun escape(value: String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    enum class HistoryReason {
        NO_HTML,NO_PLAIN_HISTORY,NO_PROVIDER_BOUNDARY,MULTIPLE_PROVIDER_BOUNDARIES,
        TRAILING_CONTENT,EMPTY_CURRENT,PLAIN_HISTORY_MISMATCH,SEPARATED
    }
    data class HistoryAnalysis(val currentHtml: String,val separateHistory: Boolean,val reason: HistoryReason,val quotedHtml: String="")
    fun noHistory(html: String)=HistoryAnalysis(html,false,HistoryReason.NO_PLAIN_HISTORY)
    private fun normalizedHistoryText(value: String)=value.replace('\u00a0',' ').replace("\uFFFC","")
        .replace(Regex("[\\s\\u200B\\uFEFF]+")," ").trim()
    private fun normalizedAttribution(value: String)=normalizedHistoryText(value).replace(Regex("\\s+:$"),":")
    // Link targets stay in the visible plain projection, but are not corroboration
    // evidence: an independent MIME plain alternative need not repeat anchor URLs.
    private fun historyText(source: String)=text(source,false)
    private fun cssQuoteBoundary(element: Element): Boolean=element.attr("style").split(';').any{declaration->
        val parts=declaration.split(':',limit=2)
        parts.size==2 && parts[0].trim().lowercase() in setOf("border-left","border-inline-start") &&
            parts[1].trim().isNotEmpty() && !parts[1].trim().equals("none",true)
    }
    private fun genericQuoteBoundary(element: Element): Boolean=element.normalName()=="blockquote" ||
        element.attr("type").equals("cite",true) || cssQuoteBoundary(element)
    private fun emptyFormattingSeparator(element: Element): Boolean {
        if(element.normalName()=="br")return true
        if(element.normalName() !in setOf("div","p","span"))return false
        return normalizedHistoryText(historyText(element.outerHtml())).isEmpty() &&
            element.select("img,table,hr,blockquote,iframe,object,video,audio").isEmpty() &&
            !element.attr("style").contains("url(",true)
    }
    private data class AttributionNodes(val nodes: List<Node>) {
        val html get()=nodes.joinToString(""){it.outerHtml()}
        val singleElement get()=nodes.filterNot{it is TextNode && it.text().isBlank()}.singleOrNull() as? Element
    }
    private fun precedingAttribution(boundary: Element): AttributionNodes? {
        var node=boundary.previousSibling();var separators=0
        while(node!=null && separators<16 &&
            (node is TextNode && node.text().isBlank() || node is Element && emptyFormattingSeparator(node))) {
            node=node.previousSibling();separators++
        }
        if(node==null)return null
        if(node is Element && node.isBlock)return AttributionNodes(listOf(node))
        // Attribution is often a sibling run: date text + <a>sender</a> + ':' .
        // Keep the whole run together; accepting only the anchor leaves a stale header.
        val nodes=mutableListOf<Node>()
        while(node!=null && nodes.size<32) {
            if(node is Element && (node.isBlock || node.normalName()=="br"))break
            nodes.add(0,node)
            node=node.previousSibling()
        }
        if(node!=null && nodes.size==32)return null
        return nodes.takeIf{it.isNotEmpty()}?.let{AttributionNodes(it)}
    }
    private fun nodePath(root: Node,node: Node): List<Int> {
        val path=mutableListOf<Int>();var current=node
        while(current!==root) {
            path.add(0,current.siblingIndex())
            current=current.parentNode()?:error("Attribution outside document")
        }
        return path
    }
    private fun resolveNode(root: Node,path: List<Int>): Node=path.fold(root){node,index->node.childNode(index)}
    private val historyAddresses=Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private fun senderLinkAttribution(element: Element,message: QuotedMessage): Boolean {
        if(element.normalName()!="a")return false
        val url=link(element.attr("href"))?.takeIf{it.startsWith("mailto:",true)}?:return false
        val address=url.substringAfter(':').substringBefore('?').trim()
        if(!historyAddresses.matches(address))return false
        if(historyAddresses.findAll(message.sender.orEmpty()).none{it.value.equals(address,true)})return false
        val visible=normalizedHistoryText(historyText(element.outerHtml()))
        return visible.equals(address,true) || visible==normalizedHistoryText(message.sender.orEmpty())
    }
    private fun contentAfter(root: Element,boundary: Element): Boolean {
        var inside=false;var after=false;var found=false
        NodeTraversor.traverse(object: NodeVisitor {
            override fun head(node: Node,depth: Int) {
                if(node===boundary){inside=true;return}
                if(!after || inside)return
                when(node) {
                    is TextNode->if(node.text().isNotBlank())found=true
                    is Element->if(node.normalName() in setOf("img","table","hr") ||
                        node.attr("style").contains("url(",true))found=true
                }
            }
            override fun tail(node: Node,depth: Int) {if(node===boundary){inside=false;after=true}}
        },root)
        return found
    }
    private fun attributionHeader(value: String): Boolean {
        val parsed=ConversationText.parse("Current reply\n\n$value\n> Quoted message")
        return parsed.messages.firstOrNull()?.attribution?.let{
            normalizedHistoryText(it)==normalizedHistoryText(value)
        }==true
    }
    /** Recognition only: retain the original HTML as the expanded representation.
     *  A plain projection corroborates a boundary but never replaces the rich history. */
    fun analyzeHistory(html: String,conversation: QuotedConversation): HistoryAnalysis {
        fun keep(reason: HistoryReason)=HistoryAnalysis(html,false,reason)
        if(html.isBlank())return HistoryAnalysis(html,conversation.messages.isNotEmpty(),HistoryReason.NO_HTML)
        if(conversation.messages.isEmpty())return keep(HistoryReason.NO_PLAIN_HISTORY)
        val doc=Jsoup.parse(html)
        val providers=doc.select(".gmail_quote,.yahoo_quoted,.protonmail_quote").toList()
        val providerRoots=providers.filter{candidate->candidate.parents().none{it in providers}}
        if(providerRoots.size>1)return keep(HistoryReason.MULTIPLE_PROVIDER_BOUNDARIES)
        val candidates=if(providerRoots.isNotEmpty())providerRoots else
            doc.select("blockquote,[type=cite],[style]").toList().filter{genericQuoteBoundary(it)}.filter{candidate->
                candidate.parents().none{genericQuoteBoundary(it)}
            }
        for(boundary in candidates.asReversed()) {
            if(contentAfter(doc.body(),boundary))continue
            val preceding=precedingAttribution(boundary)
            val precedingText=preceding?.let{historyText(it.html)}.orEmpty()
            val units=conversation.messages
            val attribution=preceding?.takeIf{
                boundary.hasClass("gmail_quote") && it.singleElement?.hasClass("gmail_attr")==true || attributionHeader(precedingText) ||
                    it.singleElement?.let{element->senderLinkAttribution(element,units.first())}==true
            }
            val bodies=setOf(normalizedHistoryText(historyText(boundary.outerHtml())),normalizedHistoryText(text(boundary.outerHtml())))
            val attributed=attribution?.let{setOf(normalizedAttribution(historyText(it.html)),normalizedAttribution(text(it.html)))}
            fun attributionMatches(message: QuotedMessage): Boolean {
                val element=attribution?:return true
                return normalizedAttribution(message.attribution.orEmpty()) in attributed.orEmpty() ||
                    element.singleElement?.let{senderLinkAttribution(it,message)}==true
            }
            val single=units.singleOrNull()?.let{message->normalizedHistoryText(message.body) in bodies &&
                attributionMatches(message)}==true
            val chain=normalizedHistoryText(units.joinToString("\n"){message->
                listOfNotNull(message.attribution,message.body).joinToString("\n")})
            val chainWithoutFirstHeader=normalizedHistoryText(units.mapIndexed{index,message->
                if(index==0)message.body else listOfNotNull(message.attribution,message.body).joinToString("\n")
            }.joinToString("\n"))
            // Reconstructing parsed messages omits divider lines consumed as headers.
            // The parser also retains its complete history body for corroboration.
            val originalBody=conversation.quotedBody?.let(::normalizedHistoryText)
            val originalWithHeader=conversation.quotedBody?.let{normalizedHistoryText(listOfNotNull(units.first().attribution,it).joinToString("\n"))}
            val completeChain=bodies.any{it.isNotEmpty() && (it==chain || it==chainWithoutFirstHeader || it==originalBody || it==originalWithHeader)} && attributionMatches(units.first())
            // Plain > prefixes and nested HTML citation elements must agree in both
            // text and depth. This is structural evidence, not a fuzzy text match.
            val structured=conversation.quotedBody?.let{plain->
                val expected=QuoteStructure.plain(plain,::attributionHeader)
                expected.isNotEmpty() && listOf(false,true).any{targets->
                    QuoteStructure.html(boundary,::attributionHeader,{element->
                        genericQuoteBoundary(element) || element.hasClass("gmail_quote") || element.hasClass("yahoo_quoted") || element.hasClass("protonmail_quote")
                    },targets)==expected
                }
            }==true && attributionMatches(units.first())
            // Some providers flatten nested HTML citations and use *emphasis* in the
            // independent plain alternative. Accept only the complete matching text,
            // with a recognized outer provider boundary and matching attribution.
            fun presentationText(source: String): String {
                val lines=source.lineSequence().map{it.trim()}.filterNot{it.matches(Regex("[-_─━]{3,}"))}.joinToString(" ")
                val emphasis=Regex("(?<![\\p{L}\\p{N}])([*_]{1,2})(\\S(?:.*?\\S)?)\\1(?![\\p{L}\\p{N}])")
                return normalizedHistoryText(emphasis.replace(lines){it.groupValues[2]})
            }
            val flattenedProvider=boundary in providerRoots && attribution!=null && attributionMatches(units.first()) &&
                conversation.quotedBody?.let{plain->
                    val runs=QuoteStructure.plain(plain,::attributionHeader)
                    runs.isNotEmpty() && runs.all{it.depth==0} &&
                        presentationText(plain)==presentationText(historyText(boundary.outerHtml()))
                }==true
            val presentation=attribution!=null && attributionMatches(units.first()) &&
                conversation.quotedBody?.let{plain->QuotePresentation.matches(plain,boundary,::attributionHeader){element->
                    genericQuoteBoundary(element) || element.hasClass("gmail_quote") || element.hasClass("yahoo_quoted") || element.hasClass("protonmail_quote")
                }}==true
            if(bodies.all{it.isEmpty()} || !single && !completeChain && !structured && !flattenedProvider && !presentation)continue
            // Generic citations need an attribution, not merely identical quoted words.
            if(providers.isEmpty() && attribution==null &&
                !attributionHeader(historyText(boundary.outerHtml()).lineSequence().firstOrNull().orEmpty()))continue
            val shortened=doc.clone()
            val path=doc.getAllElements().indexOf(boundary)
            val attributionPaths=attribution?.nodes?.map{nodePath(doc,it)}.orEmpty()
            val nodes=shortened.getAllElements().toList()
            val attributionNodes=attributionPaths.map{path->resolveNode(shortened,path)}
            attributionNodes.forEach{it.remove()};nodes[path].remove()
            if(text(shortened.outerHtml()).isBlank())return keep(HistoryReason.EMPTY_CURRENT)
            val richHistory=attribution?.html.orEmpty()+boundary.outerHtml()
            // Preserve scoped stylesheet rules for the independently rendered citation.
            val styles=doc.select("style").joinToString(""){it.outerHtml()}
            return HistoryAnalysis(shortened.outerHtml(),true,HistoryReason.SEPARATED,styles+richHistory)
        }
        if(candidates.isEmpty())return keep(HistoryReason.NO_PROVIDER_BOUNDARY)
        if(candidates.any{contentAfter(doc.body(),it)})return keep(HistoryReason.TRAILING_CONTENT)
        return keep(if(candidates.isEmpty())HistoryReason.NO_PROVIDER_BOUNDARY else HistoryReason.PLAIN_HISTORY_MISMATCH)
    }
    private fun horizontalInsets(style: String): Pair<Boolean,Boolean> {
        val sides=mutableMapOf("padding-left" to false,"padding-right" to false,"margin-left" to false,"margin-right" to false)
        fun nonzero(value: String): Boolean {
            val v=value.trim().lowercase().removeSuffix("!important").trim()
            return v.isNotEmpty() && v!="auto" && !Regex("^[+-]?0+(?:\\.0+)?(?:[a-z%]+)?$").matches(v)
        }
        style.split(';').forEach { declaration->
            val parts=declaration.split(':',limit=2)
            if(parts.size==2) {
                val name=parts[0].trim().lowercase();val value=parts[1].trim()
                if(name=="padding" || name=="margin") {
                    val values=value.split(Regex("\\s+")).filter{it.isNotBlank() && it!="!important"}
                    if(values.isNotEmpty()) {
                        sides["$name-right"]=nonzero(values.getOrElse(1){values[0]})
                        sides["$name-left"]=nonzero(values.getOrElse(3){values.getOrElse(1){values[0]}})
                    }
                } else if(sides.containsKey(name)) sides[name]=nonzero(value)
            }
        }
        return (sides["padding-left"]==true || sides["margin-left"]==true) to
            (sides["padding-right"]==true || sides["margin-right"]==true)
    }
    fun document(html: String,plain: String,attachments: List<Attachment>,history: HistoryAnalysis=noHistory(html),external: Boolean=false,
        foreground: String="#20241f",background: String="#f8f9f6",identity: String="",originalScale: Boolean=false): MailHtmlDocument {
        val currentHtml=html.takeIf{it.isNotBlank()}?.let{history.currentHtml}
        val source=currentHtml?:this.plain(plain)
        val namespace=MailImageLimits.hash(identity+source+external+attachments.joinToString{it.path+it.contentId})
        val doc=Jsoup.parseBodyFragment(cleanSource(source))
        // Serialization must preserve text nodes: .mail-plain renders their line
        // breaks and spaces with pre-wrap, and sender HTML can also preserve them.
        doc.outputSettings().prettyPrint(false)
        // White/grey page and text backgrounds blend into the selected reading theme.
        // Keep colour highlights, dark areas, gradients and background images unchanged.
        doc.select("[bgcolor]").forEach{element->if(neutralFill(element.attr("bgcolor")))element.removeAttr("bgcolor")}
        val resources=linkedMapOf<String,MailImageSource>();var hasExternal=false
        fun resource(raw: String): Pair<String,MailImageSource>? {
            val image=when {
                raw.startsWith("cid:",true)->attachments.firstOrNull{cid(it.contentId)==cid(raw.substring(4)) && it.contentId.isNotBlank() && it.mime.startsWith("image/",true) }?.let{MailImageSource.Inline(it)}
                raw.startsWith("data:",true)->Regex("(?is)^data:(image/(?:png|jpeg|gif|webp));base64,([a-z0-9+/=\\s]+)$").matchEntire(raw)?.takeIf{raw.length<2*1024*1024}?.let{MailImageSource.Data(it.groupValues[1].lowercase(),it.groupValues[2])}
                else->imageUrl(raw)?.let{MailImageSource.Remote(it)}
            }?:return null
            if(image is MailImageSource.Remote)hasExternal=true
            if(image is MailImageSource.Remote && !external)return null
            val old=resources.entries.firstOrNull{it.value==image}
            if(old!=null)return old.key to image
            if(resources.size>=64)return null
            val path="${ORIGIN}image/$namespace/${resources.size}".also{resources[it]=image}
            return path to image
        }
        doc.select("img").toList().forEach{img->
            if(img.attr("width").toIntOrNull()?.let{it<=2}==true && img.attr("height").toIntOrNull()?.let{it<=2}==true){img.remove();return@forEach}
            val resolved=resource(img.attr("src"))
            if(resolved==null)img.replaceWith(Element("span").addClass("mail-image-placeholder").text(img.attr("alt").ifBlank{"Изображение"}))
            else{img.attr("src",resolved.first);img.removeAttr("srcset")}
        }
        // CSS images use the same isolated loader; external fonts and unknown resources stay blocked.
        fun css(value: String)=transparentNeutralBackgrounds(rewriteCss(value){resource(it)?.first})
        doc.select("[style]").forEach{it.attr("style",css(it.attr("style")))}
        doc.select("style").forEach{it.html(css(it.data()))}
        doc.select("a").forEach{it.removeAttr("target");it.attr("rel","noreferrer noopener")}
        val csp="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src $ORIGIN; font-src 'none'; media-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; connect-src 'none'"
        // Data tables are independent horizontal regions. Their explicit width must
        // not reduce every surrounding paragraph to a desktop overview.
        if(!originalScale)doc.select("table").filter{table->table.parents().none{it.normalName()=="table"}}.forEach { table->
            table.wrap("<div class='mail-table-scroll'></div>")
        }
        // Normalize the display-only cut, not recognition, source MIME or the expanded quote.
        if(history.separateHistory)CollapsedReplyBoundary.normalize(doc.body())
        val page=doc.clone().apply{if(!originalScale)select("table").remove()}
        val layout=if(html.isNotBlank())layoutWidth(page)else 0
        val viewport=if(originalScale)"device-width, initial-scale=1"else if(layout>0)layout.toString()else "device-width"
        // Only the outer wrapper's own horizontal spacing replaces our inset; inner
        // element styles such as p { margin: 0 } do not provide page margins.
        val outer=doc.body().children().singleOrNull()
        val outerStyle=outer?.attr("style").orEmpty()
        val (hasLeftInset,hasRightInset)=horizontalInsets(outerStyle)
        val leftInset=if(currentHtml==null || !hasLeftInset)"16px" else "0"
        val rightInset=if(currentHtml==null || !hasRightInset)"16px" else "0"
        val adaptation=if(originalScale)"" else """
            p,div,span,a,td,th{overflow-wrap:anywhere;white-space:normal!important}
            pre,pre *{white-space:pre-wrap!important;overflow-wrap:anywhere}
            .mail-table-scroll{max-width:100%;overflow-x:auto}
            .mail-table-scroll>table{max-width:100%!important}
        """.trimIndent()
        val imageSizing=if(originalScale)""else "img{max-width:100%!important;height:auto}"
        val wrapping=if(originalScale)"normal"else "anywhere"
        val wrapped="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=$viewport, minimum-scale=0.01, maximum-scale=10"><meta http-equiv="Content-Security-Policy" content="$csp"><meta name="referrer" content="no-referrer"><style>
            html,body{margin:0;padding:0;max-width:100%;overflow-wrap:$wrapping;word-break:normal;background:$background;color:$foreground;font:16px/1.55 sans-serif}
            body{box-sizing:border-box;padding-left:$leftInset;padding-right:$rightInset}
            $adaptation
            $imageSizing a{color:#526b45;text-decoration:underline}
            .mail-plain,.mail-plain *{white-space:pre-wrap!important}.mail-image-placeholder{display:inline-block;padding:8px 12px;margin:4px 0;border:1px solid #b9c3b4;border-radius:10px;font:13px/1.4 sans-serif}
            #mail-history-toggle{display:inline-flex!important;align-items:center!important;gap:5px!important;box-sizing:border-box!important;min-width:52px!important;height:28px!important;margin:12px 0!important;padding:3px 10px!important;border:1px solid #b8b8b8!important;border-radius:999px!important;background:#e6e6e6!important;color:#191919!important;font:14px/22px sans-serif!important;text-decoration:none!important;white-space:nowrap!important}
            </style></head><body>${doc.body().html()}</body></html>""".trimIndent()
        return MailHtmlDocument(wrapped,resources,hasExternal,layout,external,originalScale)
    }
    /** One forward scan, including malformed input; nested url( text cannot cause regex backtracking. */
    private fun rewriteCss(value: String,resolve: (String)->String?): String {
        val out=StringBuilder();var cursor=0
        while(cursor<value.length) {
            val start=value.indexOf("url(",cursor,ignoreCase=true)
            if(start<0){out.append(value,cursor,value.length);break}
            out.append(value,cursor,start)
            var index=start+4
            while(index<value.length && value[index].isWhitespace())index++
            val quote=value.getOrNull(index)?.takeIf{it=='\'' || it=='"'}
            if(quote!=null)index++
            val token=StringBuilder();var ended=false
            while(index<value.length) {
                val character=value[index++]
                if(character=='\\' && index<value.length){if(token.length<=8192)token.append(value[index]);index++;continue}
                if(quote!=null && character==quote || quote==null && character==')'){ended=true;break}
                if(token.length<=8192)token.append(character)
            }
            if(ended && quote!=null) {
                while(index<value.length && value[index].isWhitespace())index++
                if(value.getOrNull(index)==')')index++ else ended=false
            }
            val target=if(ended && token.length<=8192)resolve(token.toString().trim())else null
            if(target!=null)out.append("url('").append(target).append("')")else out.append("none")
            cursor=index
        }
        return out.toString()
    }

}
