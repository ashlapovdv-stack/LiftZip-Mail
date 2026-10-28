package ru.liftzip.mail

import jakarta.mail.Address
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility

/** RFC 2047 encoded words are transport syntax, never a display name. */
object HeaderText {
    fun decode(value: String): String = runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)

    fun addresses(values: Array<out Address>?): String = values?.joinToString(", ") { address ->
        if (address is InternetAddress) address.toUnicodeString() else decode(address.toString())
    }.orEmpty()

    // Also fixes headers already cached by version 0.1, without deleting the mailbox.
    fun recipients(value: String): String = runCatching {
        addresses(InternetAddress.parseHeader(value, false))
    }.getOrElse { decode(value) }
}
