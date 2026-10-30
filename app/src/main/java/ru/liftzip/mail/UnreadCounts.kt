package ru.liftzip.mail

object UnreadCounts {
    fun cached(messages: List<MailItem>,accountId: String) = messages.count{it.accountId==accountId && SyncRules.remoteInbox(it) && it.unread && !it.pendingDelete}
    /** A flag operation completing during a poll must not be undone by the poll's older count. */
    fun reconcile(server: Int,observed: List<MailItem>,current: List<MailItem>,guarded: Set<String>): Int {
        val before=observed.associateBy{it.id}
        val delta=current.filter{it.id in guarded && it.id in before}.sumOf{m->
            (if(m.unread && !m.pendingDelete)1 else 0)-(if(before.getValue(m.id).unread)1 else 0)
        }
        return (server+delta).coerceAtLeast(0)
    }
    fun changed(count: Int,before: List<MailItem>,after: List<MailItem>): Int {
        val old=before.associateBy{it.id}
        return (count+after.filter{SyncRules.remoteInbox(it) && it.id in old}.sumOf{m->
            (if(m.unread)1 else 0)-(if(old.getValue(m.id).unread)1 else 0)
        }).coerceAtLeast(0)
    }
}
