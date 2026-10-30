package ru.liftzip.mail

enum class TrashMethod { MOVE, COPY_UID_EXPUNGE }
data class TrashFolderInfo(val name: String,val attributes: Set<String>)
data class DeleteResult(val removed: Set<String>,val errors: List<String>)

object TrashRules {
    fun method(move: Boolean,uidPlus: Boolean): TrashMethod?=when{move->TrashMethod.MOVE;uidPlus->TrashMethod.COPY_UID_EXPUNGE;else->null}
    fun destination(folders: List<TrashFolderInfo>): String? {
        val usable=folders.filter{!it.name.equals("INBOX",true) && it.attributes.none{a->a.equals("\\NoSelect",true)}}
        return usable.firstOrNull{it.attributes.any{a->a.equals("\\Trash",true)}}?.name
            ?:usable.firstOrNull{it.name.lowercase() in setOf("trash","deleted messages","deleted items","корзина","удаленные","удалённые","удаленные сообщения","удалённые сообщения","[gmail]/trash","[gmail]/корзина","inbox.trash","inbox/trash") }?.name
    }
    fun remote(m: MailItem)=SyncRules.remoteInbox(m) && m.uid>0 && m.validity>0
    fun local(m: MailItem)=m.uid==0L && m.localFolder in setOf("drafts","sent") && m.delivery!="sending"
    fun unreadAfter(count: Int,removed: List<MailItem>)=(count-removed.count{remote(it) && it.unread}).coerceAtLeast(0)
}

/** Commit only acknowledged batches. A later error never discards earlier successful work. */
object TrashTransfer {
    suspend fun <T> run(uids: List<Long>,read: suspend (List<Long>)->List<T>,uid: (T)->Long,move: suspend (List<T>)->Unit,
        before: suspend ()->Boolean,onConfirmed: suspend (Set<Long>)->Boolean,batchSize: Int=100) {
        require(uids.all{it>0} && batchSize>0)
        for(batch in uids.distinct().chunked(batchSize)) {
            if(!before())return
            val remote=read(batch)
            require(remote.all{uid(it) in batch}){"Сервер вернул другое письмо. Обновите список."}
            if(remote.isNotEmpty())move(remote)
            // Missing UIDs are already absent from this exact folder/UIDVALIDITY.
            if(!onConfirmed(batch.toSet()))return
        }
    }
}
