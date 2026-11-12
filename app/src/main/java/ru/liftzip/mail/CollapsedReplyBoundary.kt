package ru.liftzip.mail

import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** A display projection only, after a corroborated quote split. Never applied to original mail. */
internal object CollapsedReplyBoundary {
    private val flow=setOf("div","p","span","font","center","section","article","main","header","footer",
        "h1","h2","h3","h4","h5","h6","ul","ol","li","dl","dt","dd","address","b","i","u","strong","em","a")
    private val decoration=Regex("(?i)(?:^|[;{])\\s*(?:content|background(?:-[a-z-]+)?|border(?:-[a-z-]+)?|box-shadow)\\s*:")
    private fun blank(c: Char)=c.isWhitespace() || c=='\u00a0' || c=='\u200b' || c=='\ufeff'
    private fun decorated(element: Element,stylesheetPaint: Boolean)=stylesheetPaint || element.hasClass("mail-image-placeholder") || element.hasAttr("bgcolor") ||
        decoration.containsMatchIn(element.attr("style"))
    private fun edge(element: Element,padding: Boolean) {
        val original=element.attr("style").trim().trimEnd(';')
        element.attr("style",original+(if(original.isEmpty())""else ";")+"margin-bottom:0!important"+
            if(padding)";padding-bottom:0!important"else "")
    }
    fun normalize(body: Element) {
        // Sender stylesheets can paint an otherwise empty class/tag (logos or pseudo-elements).
        // Treat that authored layout as a visible object instead of deleting it as blank markup.
        val stylesheetPaint=body.ownerDocument()?.select("style")?.any{decoration.containsMatchIn(it.data())}==true
        var parent=body
        // Walk only the final content branch; earlier paragraphs and interior whitespace stay intact.
        while(true) {
            val last=parent.childNodes().lastOrNull{it !is Comment && !(it is Element && it.normalName()=="style")}
            when {
                last==null->{
                    if(parent===body)return
                    val ancestor=parent.parent()?:return
                    // Keep a stylesheet even when its otherwise blank wrapper is removed.
                    if(parent.select("style").isEmpty())parent.remove()else parent.unwrap()
                    parent=ancestor
                }
                last is TextNode->{
                    val text=last.wholeText.trimEnd(::blank)
                    if(text.isEmpty()){last.remove();continue}
                    last.text(text)
                    break
                }
                last is Element && last.normalName()=="br"->last.remove()
                last is Element && last.normalName() in flow && !decorated(last,stylesheetPaint)->parent=last
                else->{
                    // A table, picture, rule or painted footer is a visible object. Its own
                    // dimensions/padding belong to the content; only its outside margin is removed.
                    if(last is Element)edge(last,false)
                    break
                }
            }
        }
        var ancestor: Element?=parent
        while(ancestor!=null) {
            edge(ancestor,!decorated(ancestor,stylesheetPaint))
            if(ancestor===body)break
            ancestor=ancestor.parent()
        }
    }
}
