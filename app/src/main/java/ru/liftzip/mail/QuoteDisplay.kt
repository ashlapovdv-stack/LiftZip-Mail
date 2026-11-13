package ru.liftzip.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Display-only repair of provider-flattened history, after MIME boundary corroboration. */
internal object QuoteDisplay {
    fun nested(html: String,messages: List<QuotedMessage>): String {
        if(messages.size<2)return html
        val doc=Jsoup.parseBodyFragment(html)
        doc.outputSettings().prettyPrint(false)
        messages.drop(1).forEachIndexed { index,message->
            val header=message.attribution?.trim()?.takeIf{it.isNotEmpty()}?:return@forEachIndexed
            val candidates=doc.getAllElements().flatMap{it.textNodes()}.filter{it.wholeText.trim()==header}
            if(candidates.size!=1)return@forEachIndexed
            val start=candidates.single()
            val parent=start.parent() as? Element?:return@forEachIndexed
            val depth=parent.parents().count{it.normalName()=="blockquote"}+if(parent.normalName()=="blockquote")1 else 0
            if(depth>=index+2)return@forEachIndexed
            // Only split a complete, matching tail. Keep rich nodes, links and images.
            val tail=parent.childNodes().dropWhile{it!==start}
            val check=Element("div")
            tail.forEach{check.appendChild(it.clone())}
            fun words(s: String)=s.replace(Regex("\\s+")," ").trim()
            if(!words(HtmlMail.text(check.html())).endsWith(words(message.body)))return@forEachIndexed
            val quote=Element("blockquote").attr("style","border-left:2px solid #0077ff;margin:8px 0 0 10px;padding:0 0 0 10px")
            start.before(quote)
            tail.forEach{quote.appendChild(it)}
        }
        return doc.body().html()
    }
}
