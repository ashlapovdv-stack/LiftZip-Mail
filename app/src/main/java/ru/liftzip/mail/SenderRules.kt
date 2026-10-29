package ru.liftzip.mail

object SenderRules {
    fun forNewMessage(accounts: List<MailAccount>, defaultId: String?, currentId: String?): String? {
        val ids=accounts.filter{it.enabled}.map{it.id}
        return defaultId?.takeIf{it in ids} ?: currentId?.takeIf{it in ids} ?: ids.firstOrNull()
    }
}
