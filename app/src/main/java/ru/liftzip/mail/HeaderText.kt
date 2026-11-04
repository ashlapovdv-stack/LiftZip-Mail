package ru.liftzip.mail

import jakarta.mail.Address
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility

/** RFC 2047 encoded words are transport syntax, never a display name. */
object HeaderText {
    fun decode(value: String): String = runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)

    fun addresses(values: Array<out Address>?): String = values?.joinToString(", ") { address ->
        if (address !is InternetAddress) decode(address.toString())
        else {
            val name=address.personal?.let(::decode).orEmpty()
            val email=address.address.orEmpty()
            // Transport formatting unnecessarily quotes every Cyrillic name. This is UI text.
            if(name.isBlank() || email.isBlank())decode(address.toUnicodeString())
            else {
                val display=if(name.any{it in ",;\"<>\\"})"\""+name.replace("\\","\\\\").replace("\"","\\\"")+"\"" else name
                "$display <$email>"
            }
        }
    }.orEmpty()

    // Also fixes headers already cached by version 0.1, without deleting the mailbox.
    fun recipients(value: String): String = runCatching {
        addresses(InternetAddress.parseHeader(value, false))
    }.getOrElse { decode(value) }
    /** The editor accepts mailbox addresses; preserve every recipient while removing display names. */
    fun editableRecipients(value: String): String=runCatching {
        val parsed=InternetAddress.parseHeader(value,true).flatMap{address->if(address.isGroup)address.getGroup(true)?.toList().orEmpty()else listOf(address)}
        require(parsed.isNotEmpty() && parsed.all{MailRules.validEmail(it.address.orEmpty())})
        parsed.joinToString(", "){it.address}
    }.getOrDefault(value)

}
