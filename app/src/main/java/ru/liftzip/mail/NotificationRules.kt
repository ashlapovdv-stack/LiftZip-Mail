package ru.liftzip.mail

data class ArrivalCursor(val validity: Long=0,val uid: Long=0,val ready: Boolean=false)
data class ArrivalResult(val cursor: ArrivalCursor,val arrived: Set<String>)
data class NotificationPlan(val pending: Set<String>,val dismissed: Set<String>,val messages: List<MailItem>)
data class NotificationAccess(val permission: Boolean=true,val appEnabled: Boolean=true,val channelEnabled: Boolean=true,val backgroundRestricted: Boolean=false,val batteryExempt: Boolean=false) {
    val canPost get()=permission && appEnabled && channelEnabled
}
data class BackgroundCheck(val started: Long=0,val finished: Long=0,val result: String="",val error: String="",val newUnread: Int=0)
data class BackgroundRuntime(val state: String="UNKNOWN",val attempt: Int=0,val nextRunAt: Long=0)

object NotificationRules {
    const val MAX_CHILDREN=40
    fun arrivals(cursor: ArrivalCursor,validity: Long,messages: List<MailItem>,complete: Boolean): ArrivalResult {
        if(!cursor.ready || cursor.validity!=validity) {
            return ArrivalResult(if(complete)ArrivalCursor(validity,messages.maxOfOrNull{it.uid}?:0,true) else ArrivalCursor(validity),emptySet())
        }
        val arrived=messages.filter{it.uid>cursor.uid && it.unread}.map{it.id}.toSet()
        return ArrivalResult(cursor.copy(uid=maxOf(cursor.uid,messages.maxOfOrNull{it.uid}?:0)),arrived)
    }
    fun plan(previous: Set<String>,arrived: Set<String>,messages: List<MailItem>,dismissed: Set<String>): NotificationPlan {
        val pending=SyncRules.pendingNotifications(previous,arrived,messages)
        val hidden=dismissed.intersect(pending)
        return NotificationPlan(pending,hidden,messages.filter{it.id in pending && it.id !in hidden}.sortedWith(compareByDescending<MailItem>{it.date}.thenBy{it.id}))
    }
    fun body(message: MailItem): String=if(!message.contentCached)"Текст пока не загружен. Нажмите, чтобы открыть письмо." else
        ReplyText.split(message.body).current.trim().take(3000).ifBlank{if(message.attachments.isNotEmpty())"Письмо содержит вложения" else "Письмо без текста"}
    fun shortBody(message: MailItem)=body(message).replace(Regex("\\s+")," ").take(180)
    fun shouldRetry(result: SyncResult)=result.errors.isNotEmpty()
}
