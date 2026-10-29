package ru.liftzip.mail

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

data class HistoryIssue(val messageId: String,val accountId: String,val detail: String,val attempts: Int = 1)
enum class HistoryWorkState { UNKNOWN, RUNNING, WAITING, STOPPED }

object HistoryRules {
    const val MAX_BODY_ATTEMPTS=2
    const val GENERATION_TAG="history-generation:"
    fun pendingBodies(accountId: String,messages: List<MailItem>,issues: List<HistoryIssue>): List<MailItem> {
        val attempts=issues.associate{it.messageId to it.attempts}
        return messages.filter{it.accountId==accountId && it.localFolder=="inbox" && !it.contentCached && (attempts[it.id]?:0)<MAX_BODY_ATTEMPTS}
            .sortedWith(compareBy<MailItem>{attempts[it.id]?:0}.thenByDescending{it.uid})
    }
    fun remainingIssues(job: HistoryDownload,messages: List<MailItem>): List<HistoryIssue> {
        val missing=messages.filter{it.accountId in job.accountIds && it.localFolder=="inbox" && !it.contentCached}.map{it.id}.toSet()
        return job.issues.filter{it.messageId in missing}
    }
    fun afterBody(job: HistoryDownload,previous: List<MailItem>,batch: ContentBatch,now: Long): HistoryDownload {
        val resolved=batch.messages.map{it.id}.toSet()+batch.missingIds
        val issues=job.issues.filter{it.messageId !in resolved}.associateBy{it.messageId}.toMutableMap()
        for((id,detail) in batch.failures) {
            val m=previous.find{it.id==id}?:continue
            val old=issues[id]
            issues[id]=HistoryIssue(id,m.accountId,detail,((old?.attempts?:0)+1).coerceAtMost(MAX_BODY_ATTEMPTS))
        }
        return job.copy(issues=issues.values.toList(),activityAt=now,savedAt=if(batch.messages.isNotEmpty() || batch.missingIds.isNotEmpty())now else job.savedAt)
    }
    fun workState(states: Collection<String>)=when {
        "RUNNING" in states->HistoryWorkState.RUNNING
        states.any{it=="ENQUEUED" || it=="BLOCKED"}->HistoryWorkState.WAITING
        else->HistoryWorkState.STOPPED
    }
    fun acceptsGeneration(tags: Set<String>,id: String)=tags.none{it.startsWith(GENERATION_TAG)} || GENERATION_TAG+id in tags
    fun mayRestart(job: HistoryDownload,work: HistoryWorkState,now: Long)=job.active &&
        (work==HistoryWorkState.STOPPED || job.activityAt>0 && now-job.activityAt>=90_000)
}

/** Each completed body is committed before the next network read starts. */
object HistoryBodies {
    suspend fun transfer(messages: List<MailItem>,read: suspend (MailItem)->MailItem?,save: suspend (ContentBatch)->Boolean,describe: (Exception)->String,before: suspend (MailItem)->Boolean={true},
        around: suspend (MailItem,suspend ()->Boolean)->Boolean={_,action->action()}) {
        for(message in messages) {
            val more=around(message) item@{
                if(!before(message))return@item false
                val result=try {
                    val full=read(message)
                    if(full==null)ContentBatch(emptyList(),setOf(message.id)) else ContentBatch(listOf(full),emptySet())
                }catch(e: CancellationException){throw e}
                catch(e: Exception){ContentBatch(emptyList(),emptySet(),mapOf(message.id to describe(e)))}
                save(result) && result.failures.isEmpty() // Reconnect before another message after an error.
            }
            if(!more)return
        }
    }
}

class HistoryReadTimeoutException : SocketTimeoutException("Превышено время чтения одного письма.")
class MailReadBudget(private val limitMs: Long=30_000,private val clock: ()->Long={System.nanoTime()/1_000_000},private val checkCancelled: ()->Unit={}) {
    private val started=clock()
    fun check(){checkCancelled();if(clock()-started>=limitMs)throw HistoryReadTimeoutException()}
}
