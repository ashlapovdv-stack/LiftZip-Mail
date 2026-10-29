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
data class SyncCheck(val time: Long = 0, val error: String = "",val durationMs: Long = 0)
data class FlagChange(val updated: Set<String>, val errors: List<String>)
data class HistoryStep(val more: Boolean = false, val failedAccount: String? = null, val retry: Boolean = false)

object SyncRules {
    fun needsHistory(accountId: String,messages: List<MailItem>,history: InboxHistory?)=
        history?.hasOlder!=false || messages.any{it.accountId==accountId && it.localFolder=="inbox" && !it.contentCached}
    fun activeMessages(messages: List<MailItem>,accounts: List<MailAccount>,selected: String?): List<MailItem> {
        if(selected!=null)return messages.filter{it.accountId==selected}
        val ids=accounts.filter{it.enabled}.map{it.id}.toSet()
        return messages.filter{it.accountId in ids}
    }
    private fun latestContent(incoming: MailItem,current: MailItem?,protectFlags: Boolean): MailItem {
        var result=incoming
        if(current?.contentCached==true)result=result.copy(body=current.body,attachments=current.attachments,contentCached=true,previewOnly=current.previewOnly)
        if(current!=null && protectFlags)result=result.copy(unread=current.unread,starred=current.starred)
        return result
    }
    fun checkedPage(captured: List<MailItem>,current: List<MailItem>,page: InboxPage,protected: Set<String>): List<MailItem> {
        if(page.history.total==0)return emptyList()
        val validity=page.validity
        val knownWindow=captured.filter{it.localFolder=="inbox"}.map{it.id}.toSet()
        val byId=current.associateBy{it.id}
        val ids=page.messages.map{it.id}.toSet()
        val keep=current.filter{it.validity==validity && it.id !in ids && (it.localFolder=="unread-cache" || it.id !in knownWindow)}
        return keep+page.messages.map{latestContent(it,byId[it.id],it.id in protected)}
    }
    fun historyPage(current: List<MailItem>,page: InboxPage,protected: Set<String>): List<MailItem> {
        if(page.history.total==0)return emptyList()
        val ids=page.messages.map{it.id}.toSet()
        val byId=current.associateBy{it.id}
        return current.filter{it.validity==page.validity && it.id !in ids}+page.messages.map{latestContent(it,byId[it.id],it.id in protected)}
    }
    fun unreadPage(captured: List<MailItem>,current: List<MailItem>,page: UnreadPage,protected: Set<String>): List<MailItem> {
        val ids=page.messages.map{it.id}.toSet()
        val known=captured.map{it.id}.toSet()
        val byId=current.associateBy{it.id}
        return current.filter{it.validity==page.validity && it.id !in ids}.map{if(it.id in known && it.id !in protected)it.copy(unread=false)else it}+
            page.messages.map{latestContent(it,byId[it.id],it.id in protected)}
    }
    fun contentBatch(current: List<MailItem>,batch: ContentBatch): List<MailItem> {
        val byId=batch.messages.associateBy{it.id}
        return current.filter{it.id !in batch.missingIds}.map{m->byId[m.id]?.copy(unread=m.unread,starred=m.starred,localFolder=m.localFolder)?:m}
    }
    fun remoteInbox(message: MailItem)=message.localFolder=="inbox" || message.localFolder=="unread-cache"
    fun mergeWindow(cached: List<MailItem>,page: InboxPage): List<MailItem> {
        val validity=page.messages.firstOrNull()?.validity
        val ids=page.messages.map{it.id}.toSet()
        val separate=cached.filter{it.localFolder=="unread-cache" && it.validity==validity && it.id !in ids}
        return separate+page.messages
    }
    fun mergeUnread(cached: List<MailItem>,page: UnreadPage): List<MailItem> {
        val ids=page.messages.map{it.id}.toSet()
        return cached.filter{it.validity==page.validity && it.id !in ids}.map{it.copy(unread=false)}+page.messages
    }
    fun pendingNotifications(previous: Set<String>, arrived: Set<String>, messages: List<MailItem>): Set<String> {
        val unread = messages.asSequence().filter { it.localFolder == "inbox" && it.unread }.map { it.id }.toSet()
        return (previous + arrived).intersect(unread)
    }

    fun nextHistoryAccount(job: HistoryDownload, accounts: List<MailAccount>, history: Map<String, InboxHistory>, skipped: Set<String>): String? {
        if (!job.active) return null
        val available = accounts.filter{it.enabled}.map { it.id }.toSet()
        return job.accountIds.firstOrNull { it in available && it !in skipped && history[it]?.hasOlder != false }
    }

    fun sameInbox(old: MailAccount, updated: MailAccount) =
        old.imapHost.equals(updated.imapHost, true) && old.username == updated.username &&
            old.imapPort == updated.imapPort && old.imapStartTls == updated.imapStartTls

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
