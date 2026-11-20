package ru.liftzip.mail

/** Addresses found in locally cached correspondence are suggestions, not saved contacts. */
internal data class RecipientSuggestion(val email: String, val label: String, val saved: Boolean)

internal object RecipientSuggestions {
    private val address = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    fun currentToken(value: String): String =
        value.substringAfterLast(',').substringAfterLast(';').trim()

    fun replaceToken(value: String, email: String): String {
        val index = maxOf(value.lastIndexOf(','), value.lastIndexOf(';'))
        val prefix = if(index < 0) "" else value.substring(0,index + 1).trimEnd() + " "
        return prefix + email
    }

    fun search(contacts: List<MailContact>, messages: List<MailItem>, input: String): List<RecipientSuggestion> {
        val term = currentToken(input).lowercase(java.util.Locale.ROOT)
        if(term.isBlank()) return emptyList()
        val results = linkedMapOf<String,RecipientSuggestion>()
        contacts.forEach { contact ->
            contact.emails.forEach { email ->
                val key = ContactAddresses.normalize(email)
                if(contact.name.lowercase(java.util.Locale.ROOT).contains(term) || key.contains(term))
                    results[key] = RecipientSuggestion(email,contact.name,true)
            }
        }
        messages.asSequence().sortedByDescending { it.date }.take(1500).forEach { message ->
            val fields = listOf(message.senderEmail, message.recipients, message.cc, message.bcc)
            fields.forEachIndexed { index, field ->
                address.findAll(field).forEach { match ->
                    val email = match.value
                    val key = ContactAddresses.normalize(email)
                    val label = if(index == 0 && message.sender.isNotBlank()) message.sender else email
                    if(key !in results && (key.contains(term) || label.lowercase(java.util.Locale.ROOT).contains(term)))
                        results[key] = RecipientSuggestion(email,label,false)
                }
            }
        }
        return results.values.take(8)
    }
}
