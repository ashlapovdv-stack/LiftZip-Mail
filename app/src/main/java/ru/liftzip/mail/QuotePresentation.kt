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
        var normalized=emphasis.replace(plain){match->
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
        val expected=QuoteStructure.plain(normalized,header).map{it.copy(text=text(it.text))}
        if(expected.isEmpty())return false
        return listOf(false,true).any{includeAlt->
            val html=root.clone()
            if(includeAlt)html.select("img[alt]").forEach{img->img.replaceWith(TextNode(" "+img.attr("alt")+" "))}
            QuoteStructure.html(html,header,isQuote).map{it.copy(text=text(it.text))}==expected
        }
    }
}
