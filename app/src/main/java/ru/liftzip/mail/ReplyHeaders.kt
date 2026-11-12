package ru.liftzip.mail

import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress

object ReplyHeaders {
    fun read(part: Part): MessageHeaders {
        fun header(name: String)=part.getHeader(name)?.joinToString(" ").orEmpty()
        return MessageHeaders(MessageThread.id(header("Message-ID")).orEmpty(),addresses(header("Reply-To")),
            MessageThread.id(header("In-Reply-To")).orEmpty(),MessageThread.ids(header("References")))
    }
    fun addresses(raw: String): String=runCatching {
        if(raw.contains(Regex("\\r?\\n(?![ \\t])")))return ""
        InternetAddress.parseHeader(raw.replace(Regex("\\r?\\n[ \\t]+")," "),true)
            .map{it.apply{validate()}.address}.filter{MailRules.validEmail(it)}.joinToString(", ")
    }.getOrDefault("")
    fun recipientList(raw: String,incomplete: Boolean=false): List<InternetAddress> {
        if(!incomplete)require(!raw.contains('\r')){"Некорректный адрес получателя."}
        return MailRules.recipients(raw).mapNotNull{value->
            if(incomplete)if(!MailRules.validEmail(value))null else runCatching{InternetAddress(value,true).apply{validate()}}.getOrNull()
            else {require(MailRules.validEmail(value)){"Некорректный адрес получателя."};InternetAddress(value,true).apply{validate()}}
        }
    }
    fun recipient(m: MailItem)=m.headers?.replyTo?.takeIf{it.isNotBlank()}?:m.senderEmail
}
