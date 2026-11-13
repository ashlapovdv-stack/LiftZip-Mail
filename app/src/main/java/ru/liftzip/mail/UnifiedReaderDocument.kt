package ru.liftzip.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * One-WebView body builder. Both fragments go through the production
 * sanitizer. The resulting markup is DATA, never executable script.
 */
internal object UnifiedReaderDocument {
    const val CURRENT_ID = "mail-current-message"
    const val HISTORY_ID = "mail-quoted-history"

    const val TOGGLE_URL = "https://mail.liftzip.invalid/reader/toggle-history"

    fun assemble(currentHtml: String, quotedHtml: String, historyExpanded: Boolean, count: Int = 1): String {
        val doc = Jsoup.parseBodyFragment("")
        doc.outputSettings().prettyPrint(false)
        val current = Element("section").attr("id", CURRENT_ID)
        current.append(HtmlMail.cleanSource(currentHtml))
        CollapsedReplyBoundary.normalize(current)
        doc.body().appendChild(current)
        if (quotedHtml.isNotBlank()) {
            val control = Element("a").attr("id", "mail-history-toggle").attr("href", TOGGLE_URL)
                .attr("role", "button")
                .attr("aria-label", "Предыдущие сообщения: $count")
                .attr("aria-expanded", historyExpanded.toString())
                .text("⋯ $count")
            doc.body().appendChild(control)
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
