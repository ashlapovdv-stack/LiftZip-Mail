package ru.liftzip.mail

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Corroborate independent MIME alternatives without discarding meaningful words or quote depth.
 * Presentation markers are optional only when the HTML supplies the corresponding structure. */
internal object QuotePresentation {
    private fun text(value: String)=value.replace('\u00a0',' ').replace("\uFFFC","")
        .replace(Regex("[\\s\\u200B\\uFEFF]+")," ").trim()
        .replace(Regex("\\s+([.,;:!?])"),"$1")
    private val emphasis=Regex("(?<![\\p{L}\\p{N}])([*_]{1,2})(\\S(?:.*?\\S)?)\\1(?![\\p{L}\\p{N}])",RegexOption.DOT_MATCHES_ALL)
    private val bullet=Regex("^(\\s*(?:>\\s*)*)(?:[*+-]|\\d+[.)])\\s+(.+)$")
    private val annotation=Regex("\\(\\s*(https?://[^\\s<>]+?)\\s*\\)")
    private val phone=Regex("(\\+?[\\d(][\\d() \\-]{6,}\\d)\\s*\\(\\s*tel:([+\\d() \\-]+)\\s*\\)")
    private fun digits(value: String)=value.filter{it.isDigit()}.let{if(it.length==11 && it.startsWith('7'))it.drop(1)else it}

    private const val address="[A-Za-z0-9.!#$%&'*+/=?^`{|}~-]+@[A-Za-z0-9.-]+"
    private val quotePrefix=Regex("^(?:[ \t]*>[ \t]*)*")
    private val angleAddress=Regex("""\s*<\s*($address)\s*>""")
    private val composeAttribution=Regex("""(?i)^(.*?(?:\s|<))($address)\s*\(\s*((?:https:)?//e\.mail\.ru/compose/\?mailto=[^\s()]+)\s*\)(\s*>?\s*:)$""")
    private val wrappedAngle=Regex("""^(.*?<)($address)\s*>:$""")

    private fun headerText(value: String)=angleAddress.replace(value){" <${it.groupValues[1]}>"}

    private fun normalizeComposeLink(value: String): String {
        val match=composeAttribution.matchEntire(value)?:return value
        val address=match.groupValues[2]
        val target=match.groupValues[3].substringAfter("?mailto=","")
        val decoded=runCatching{java.net.URLDecoder.decode(target.replace("+","%2B"),"UTF-8")}.getOrNull()
        return if(decoded.equals("mailto:$address",true))match.groupValues[1]+address+match.groupValues[4] else value
    }

    /** Rejoin only attribution continuations. A > closing an email address belongs
     * to the header even when it is at the beginning of a continuation line. */
    private fun joinAttributions(source: String,header: (String)->Boolean): String {
        val lines=source.lines()
        val result=mutableListOf<String>()
        var i=0
        while(i<lines.size) {
            val line=lines[i++]
            val prefix=quotePrefix.find(line)!!.value
            val first=line.drop(prefix.length).trim()
            val sameLine=normalizeComposeLink(first)
            if(sameLine!=first && header(sameLine)) {
                result+=prefix+sameLine
                continue
            }
            if(i<lines.size && '@' in first && !header(first)) {
                var next=lines[i].trimStart()
                val depth=prefix.count{it=='>'}
                repeat(depth){if(next.startsWith('>'))next=next.drop(1).trimStart()else next=""}
                val combined=when {
                    next.startsWith('(') || first.endsWith('(')->normalizeComposeLink("$first $next").takeIf{it!="$first $next"}
                    next==">:"->"$first>:".takeIf{wrappedAngle.matches(it)}
                    else->null
                }
                if(combined!=null && header(combined)) {
                    result+=prefix+combined
                    i++
                    continue
                }
            }
            result+=line
        }
        return result.joinToString("\n")
    }

    fun matches(plain: String,boundary: Element,header: (String)->Boolean,isQuote: (Element)->Boolean): Boolean {
        val root=boundary.clone()
        root.select("head,style,script,noscript,[hidden]").remove()
        root.select("[style]").toList().filter{it.attr("style").contains(Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)"))}.forEach{it.remove()}
        val styled=root.getAllElements().filter { element->
            element.normalName() in setOf("b","strong","i","em") ||
                Regex("(?i)(?:^|;)\\s*(?:font-weight\\s*:\\s*(?:bold|[6-9]00)|font-style\\s*:\\s*italic)(?:\\s*!important)?\\s*(?:;|$)").containsMatchIn(element.attr("style"))
        }.map{ text(it.text()) }.toSet()
        val links=root.select("a[href]").map{it.attr("href")}.toSet()
        val items=root.select("li").map{text(it.text())}
        var normalized=emphasis.replace(joinAttributions(plain,header)){match->
            if(text(match.groupValues[2]) in styled)match.groupValues[2]else match.value
        }
        normalized=normalized.lineSequence().joinToString("\n"){line->
            val match=bullet.matchEntire(line)
            if(match!=null && items.any{it==text(match.groupValues[2]) || it.startsWith(text(match.groupValues[2])+" ")})
                match.groupValues[1]+match.groupValues[2] else line
        }
        normalized=annotation.replace(normalized){match->if(match.groupValues[1] in links)""else match.value}
        // A sanitized tel link no longer has an href. Its plain annotation may be
        // omitted only when it repeats the immediately preceding visible phone number.
        normalized=phone.replace(normalized){match->
            val visible=digits(match.groupValues[1]);val target=digits(match.groupValues[2])
            if(visible.length>=7 && visible==target)match.groupValues[1]else match.value
        }
        val expected=QuoteStructure.plain(normalized,header,::headerText).map{it.copy(text=text(it.text))}
        if(expected.isEmpty())return false
        return listOf(false,true).any{includeAlt->
            val html=root.clone()
            if(includeAlt)html.select("img[alt]").forEach{img->img.replaceWith(TextNode(" "+img.attr("alt")+" "))}
            QuoteStructure.html(html,header,isQuote,normalizeHeader=::headerText).map{it.copy(text=text(it.text))}==expected
        }
    }
}
