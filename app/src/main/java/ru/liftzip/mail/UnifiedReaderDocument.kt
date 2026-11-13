package ru.liftzip.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Experimental one-WebView body builder. Both fragments go through the production
 * sanitizer. The resulting markup is DATA, never executable script.
 *
 * This does not control the live WebView yet. It is a testable boundary for
 * the subsequent reader integration.
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
            val history = Element("section").attr("id", HISTORY_ID)
            // HTML's hidden attribute is removed by HtmlMail's Safelist.
            // Use a style understood by the renderer without enabling JavaScript.
            if (!historyExpanded) history.attr("style", "display:none")
            history.append(HtmlMail.cleanSource(quotedHtml))
            doc.body().appendChild(history)
        }
        return doc.body().html()
    }
}
