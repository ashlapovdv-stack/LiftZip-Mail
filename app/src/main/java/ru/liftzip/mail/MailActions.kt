package ru.liftzip.mail

enum class ActionKind { FLAGS, TRASH, SEND, APPEND_DRAFT, APPEND_SENT, REMOVE_DRAFT }
data class MailAction(val id: String,val accountId: String,val messageId: String,val kind: ActionKind,val payload: String,
    val revision: Long=1,val subject: String="",val serverKey: String="",val unread: Boolean?=null,val starred: Boolean?=null,
    val created: Long=System.currentTimeMillis(),val attempt: Int=0,val nextAttempt: Long=0,val status: String="pending",val error: String="",val lease: String="") {
    val active get()=status in setOf("pending","running","sending","auth")
    val title get()=when(kind){ActionKind.FLAGS->"Статус письма";ActionKind.TRASH->"Удаление";ActionKind.SEND->"Отправка";ActionKind.APPEND_DRAFT->"Черновик на сервере";ActionKind.APPEND_SENT->"Копия в Отправленных";ActionKind.REMOVE_DRAFT->"Удаление черновика"}
}

object ActionRules {
    fun ready(action: MailAction,accounts: List<MailAccount>,now: Long)=action.status=="pending" && action.nextAttempt<=now && accounts.any{it.id==action.accountId && it.enabled}
    fun retryAt(now: Long,attempt: Int)=now+minOf(15*60_000L,30_000L*(1L shl attempt.coerceIn(0,5)))
    fun recovered(action: MailAction,process: String)=if(action.status in setOf("running","sending") && action.lease!=process) {
        if(action.kind==ActionKind.SEND)action.copy(status="uncertain",error="Отправка была прервана. Проверьте доставку перед повтором.",lease="")
        else action.copy(status="pending",lease="")
    }else action
    fun overlay(items: List<MailItem>,actions: List<MailAction>): List<MailItem> {
        val pending=actions.filter{it.kind==ActionKind.FLAGS && it.active}.associateBy{it.messageId}
        val deleting=actions.filter{it.kind in setOf(ActionKind.TRASH,ActionKind.REMOVE_DRAFT) && it.active}.map{it.messageId}.toSet()
        return items.map{m->val a=pending[m.id];m.copy(unread=a?.unread?:m.unread,starred=a?.starred?:m.starred,pendingDelete=m.id in deleting)}
    }
    fun protectedIds(actions: List<MailAction>)=actions.filter{it.kind in setOf(ActionKind.FLAGS,ActionKind.TRASH)}.map{it.messageId}.toSet()
}
