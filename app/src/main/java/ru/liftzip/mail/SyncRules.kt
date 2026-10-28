package ru.liftzip.mail

data class PollSettings(val foregroundMinutes: Int = 1, val backgroundMinutes: Int = 15) {
    companion object {
        val foregroundChoices = listOf(1, 5, 15, 30, 60)
        val backgroundChoices = listOf(15, 30, 60, 120, 0)
        fun normalized(foreground: Int, background: Int) = PollSettings(
            foreground.takeIf { it in foregroundChoices } ?: 1,
            background.takeIf { it in backgroundChoices } ?: 15
        )
    }
}

data class HistoryDownload(
    val id: String = "", val accountIds: List<String> = emptyList(),
    val status: String = "idle", val error: String = ""
) {
    val active get() = status == "running" || status == "waiting"
}
data class SyncCheck(val time: Long = 0, val error: String = "")
data class FlagChange(val updated: Set<String>, val errors: List<String>)
data class HistoryStep(val more: Boolean = false, val failedAccount: String? = null, val retry: Boolean = false)

object SyncRules {
    fun pendingNotifications(previous: Set<String>, arrived: Set<String>, messages: List<MailItem>): Set<String> {
        val unread = messages.asSequence().filter { it.localFolder == "inbox" && it.unread }.map { it.id }.toSet()
        return (previous + arrived).intersect(unread)
    }

    fun nextHistoryAccount(job: HistoryDownload, accounts: List<MailAccount>, history: Map<String, InboxHistory>, skipped: Set<String>): String? {
        if (!job.active) return null
        val available = accounts.map { it.id }.toSet()
        return job.accountIds.firstOrNull { it in available && it !in skipped && history[it]?.hasOlder != false }
    }

    fun sameInbox(old: MailAccount, updated: MailAccount) =
        old.imapHost.equals(updated.imapHost, true) && old.username == updated.username

    fun newUnreadIds(old: List<MailItem>, incoming: List<MailItem>, initialized: Boolean): Set<String> {
        if (!initialized) return emptySet()
        if (old.isNotEmpty() && incoming.isNotEmpty() && old.first().validity != incoming.first().validity) return emptySet()
        val known = old.map { it.id }.toSet()
        return incoming.asSequence().filter { it.unread && it.id !in known }.map { it.id }.toSet()
    }
}

data class InboxPosition(val id: String? = null, val index: Int = 0, val offset: Int = 0) : java.io.Serializable {
    val atTop get() = index == 0 && offset == 0
    fun indexIn(ids: List<String>): Int = if (atTop) 0 else
        id?.let { ids.indexOf(it).takeIf { index -> index >= 0 } } ?: index.coerceIn(0, (ids.size - 1).coerceAtLeast(0))
}
