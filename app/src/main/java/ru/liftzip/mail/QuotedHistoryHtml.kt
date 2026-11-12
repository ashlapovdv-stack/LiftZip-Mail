package ru.liftzip.mail

import org.jsoup.Jsoup

/** Display projection only: text citations share the reader's native zoom/scroll window.
 * No sender string becomes markup; MIME and stored source remain unchanged. */
internal object QuotedHistoryHtml {
    fun render(conversation: QuotedConversation): String {
        val doc=Jsoup.parseBodyFragment("")
        doc.outputSettings().prettyPrint(false)
        conversation.messages.forEachIndexed { index,message->
            val block=doc.body().appendElement("div").attr("style",
                "margin:0 0 18px ${index.coerceAtMost(4)*8}px;border-left:2px solid #a58ac7;padding-left:10px")
            val heading=message.attribution?.takeIf{it.isNotBlank()}?:listOfNotNull(message.date,message.sender?.let{"от $it"})
                .joinToString(" ").let{if(it.isNotBlank())"$it:" else "Предыдущее сообщение:"}
            block.appendElement("div").attr("style","font-size:14px;color:#4a4a4a").text(heading)
            if(message.recipients!=null || message.subject!=null) {
                listOf("От" to message.sender,"Кому" to message.recipients,"Дата" to message.date,"Тема" to message.subject)
                    .forEach{(label,value)->value?.let{block.appendElement("div").attr("style","font-size:14px").text("$label: $it")}}
            }
            block.appendElement("div").attr("style","padding-top:8px")
                .html(HtmlMail.plain(message.body.ifBlank{"Нет текстового содержимого."}))
        }
        return doc.body().html()
    }
}
