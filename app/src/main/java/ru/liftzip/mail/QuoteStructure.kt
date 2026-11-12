package ru.liftzip.mail

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Exact content/depth evidence for MIME alternatives: > markers and blockquotes encode the same structure. */
internal object QuoteStructure {
    data class Run(val depth: Int,val text: String)
    private val blocks=setOf("br","p","div","tr","li","blockquote","h1","h2","h3","h4","h5","h6","hr")
    private class Runs(private val header: (String)->Boolean) {
        val values=mutableListOf<Run>()
        fun line(source: String,structuralDepth: Int) {
            var text=source.trim();var depth=structuralDepth
            while(text.startsWith('>')){depth++;text=text.drop(1).trimStart()}
            text=text.replace('\u00a0',' ').replace("\uFFFC","").replace(Regex("[\\s\\u200B\\uFEFF]+")," ").trim()
            if(text.isEmpty())return
            if(header(text))text=text.replace(Regex("\\s+:$"),":")
            val previous=values.lastOrNull()
            if(previous?.depth==depth)values[values.lastIndex]=previous.copy(text=previous.text+" "+text)
            else values+=Run(depth,text)
        }
    }
    fun plain(source: String,header: (String)->Boolean): List<Run> {
        val runs=Runs(header)
        source.lineSequence().forEach{runs.line(it,0)}
        return runs.values
    }
    fun html(source: Element,header: (String)->Boolean,isQuote: (Element)->Boolean,linkTargets: Boolean=false): List<Run> {
        val root=source.clone()
        root.select("head,style,script,noscript,[hidden]").remove()
        root.select("[style]").filter{it.attr("style").contains(Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)"))}.forEach{it.remove()}
        if(linkTargets)root.select("a[href]").forEach{a->HtmlMail.link(a.attr("href"))?.let{url->if(a.text().trim()!=url)a.appendText(" ($url)")}}
        val runs=Runs(header);val line=StringBuilder();var depth=0
        fun flush(){runs.line(line.toString(),depth);line.setLength(0)}
        NodeTraversor.traverse(object: NodeVisitor {
            override fun head(node: Node,level: Int) {
                if(node is TextNode)line.append(node.text())
                if(node is Element) {
                    if(node!==root && isQuote(node)){flush();depth++}
                    if(node.normalName() in blocks)flush()
                }
            }
            override fun tail(node: Node,level: Int) {
                if(node is Element) {
                    if(node.isBlock || node.normalName()=="br")flush()
                    if(node!==root && isQuote(node)){flush();depth--}
                }
            }
        },root)
        flush()
        return runs.values
    }
}
