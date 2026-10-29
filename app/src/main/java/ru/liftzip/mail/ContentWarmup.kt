package ru.liftzip.mail

import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
data class BodyFailure(val attempts: Int,val retryAt: Long,val error: String)
data class BodyCacheStatus(val running: Boolean=false,val pending: Int=0,val saved: Int=0,val finished: Long=0,val error: String="")
data class BodyWarmupResult(val saved: Int,val pending: Int,val eligible: Boolean)

/** A request arriving while a scan finishes is retained; bursts need just one extra scan. */
class BodyWarmupSignals {
    private val requests=Channel<Unit>(Channel.CONFLATED)
    fun request(){requests.trySend(Unit)}
    suspend fun collect(visible: ()->Boolean,prepare: suspend ()->BodyWarmupResult,onError: (Exception)->Unit={}) {
        for(signal in requests){
            if(!visible())continue
            try{do{if(!prepare().eligible)break}while(visible())}
            catch(e: CancellationException){throw e}catch(e: Exception){onError(e)}
        }
    }
}

/** Message storage is the durable queue. Notification permission and dismissal never gate caching. */
object ContentCacheRules {
    const val RECENT_PER_ACCOUNT=100
    const val BATCH_SIZE=3
    fun rank(message: MailItem,priority: Set<String>)=when{message.id in priority->0;message.unread->1;else->2}
    fun pending(messages: List<MailItem>,accounts: List<MailAccount>,priority: Set<String> = emptySet()): List<MailItem> {
        val active=accounts.filter{it.enabled}.map{it.id}.toSet()
        val byDate=compareBy<MailItem>{it.date}.thenBy{it.uid}.thenBy{it.id}
        val recent=mutableMapOf<String,PriorityQueue<MailItem>>()
        val missing=linkedMapOf<String,MailItem>()
        for(m in messages){
            if(m.accountId !in active || !SyncRules.remoteInbox(m))continue
            val window=recent.getOrPut(m.accountId){PriorityQueue(RECENT_PER_ACCOUNT,byDate)}
            if(window.size<RECENT_PER_ACCOUNT)window.add(m)
            else if(byDate.compare(m,window.peek())>0){window.poll();window.add(m)}
            if(!m.contentCached && (m.unread || m.id in priority))missing[m.id]=m
        }
        recent.values.forEach{window->window.filter{!it.contentCached}.forEach{missing[it.id]=it}}
        return missing.values.sortedWith(compareBy<MailItem>{rank(it,priority)}.thenByDescending{it.date}.thenByDescending{it.uid}.thenBy{it.id})
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
