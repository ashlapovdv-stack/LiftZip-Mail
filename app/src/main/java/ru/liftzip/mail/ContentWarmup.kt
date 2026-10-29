package ru.liftzip.mail

data class BodyFailure(val attempts: Int,val retryAt: Long,val error: String)
data class BodyCacheStatus(val running: Boolean=false,val pending: Int=0,val saved: Int=0,val finished: Long=0,val error: String="")
data class BodyWarmupResult(val saved: Int,val pending: Int,val eligible: Boolean)

/** Message storage is the durable queue. Notification permission and dismissal never gate caching. */
object ContentCacheRules {
    const val RECENT_PER_ACCOUNT=100
    const val BATCH_SIZE=3
    fun rank(message: MailItem,priority: Set<String>)=when{message.id in priority->0;message.unread->1;else->2}
    fun pending(messages: List<MailItem>,accounts: List<MailAccount>,priority: Set<String> = emptySet()): List<MailItem> {
        val active=accounts.filter{it.enabled}.map{it.id}.toSet()
        val inbox=messages.filter{it.accountId in active && SyncRules.remoteInbox(it)}
        val recent=inbox.groupBy{it.accountId}.values.flatMap{it.sortedByDescending{m->m.date}.take(RECENT_PER_ACCOUNT)}.map{it.id}.toSet()
        return inbox.filter{!it.contentCached && (it.unread || it.id in priority || it.id in recent)}
            .sortedWith(compareBy<MailItem>{rank(it,priority)}.thenByDescending{it.date}.thenBy{it.id})
    }
    fun eligible(pending: List<MailItem>,failures: Map<String,BodyFailure>,now: Long)=pending.filter{(failures[it.id]?.retryAt?:0)<=now}
    fun batches(pending: List<MailItem>,priority: Set<String>,served: Map<String,Int>): List<List<MailItem>> = pending.groupBy{it.accountId}.entries
        .sortedWith(compareBy<Map.Entry<String,List<MailItem>>>{rank(it.value.first(),priority)}.thenBy{served[it.key]?:-1})
        .take(2).map{it.value.take(BATCH_SIZE)}
    fun failed(previous: BodyFailure?,now: Long,error: String): BodyFailure {
        val attempts=((previous?.attempts?:0)+1).coerceAtMost(20)
        val delay=(15_000L*(1L shl (attempts-1).coerceAtMost(5))).coerceAtMost(300_000L)
        return BodyFailure(attempts,now+delay,error)
    }
}
