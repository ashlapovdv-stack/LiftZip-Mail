package ru.liftzip.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Experimental single-document representation of a message and its quoted history.
 *
 * This is deliberately a pure HTML transformation. It does not create another
 * WebView, enable JavaScript, or change the production reader's gesture routing.
 * Both fragments are sanitized by the existing mail sanitizer before assembly.
 */
internal object UnifiedReaderDocument {
    const val CURRENT_ID = "mail-current-message"
    const val HISTORY_ID = "mail-quoted-history"

    fun assemble(currentHtml: String, quotedHtml: String, historyExpanded: Boolean): String {
        val doc = Jsoup.parseBodyFragment("")
        doc.outputSettings().prettyPrint(false)
        val current = Element("section").attr("id", CURRENT_ID)
        current.append(HtmlMail.cleanSource(currentHtml))
        doc.body().appendChild(current)
        if (quotedHtml.isNotBlank()) {
            val history = Element("section")
                .attr("id", HISTORY_ID)
            if (!historyExpanded) history.attr("hidden", "")
            history.append(HtmlMail.cleanSource(quotedHtml))
            doc.body().appendChild(history)
        }
        return doc.body().html()
    }
}
