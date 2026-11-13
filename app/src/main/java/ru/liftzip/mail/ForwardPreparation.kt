package ru.liftzip.mail

import java.io.Serializable

enum class ForwardStatus { WAITING, DOWNLOADING, READY, ERROR }

data class ForwardFile(
    val key: String, val name: String, val mime: String, val size: Long,
    val path: String? = null, val sourceUri: String? = null,
    val status: ForwardStatus = ForwardStatus.WAITING, val bytes: Long = 0,
    val prepared: OutgoingFile? = null, val error: String = "", val contentId: String=""
) : Serializable

/** Only source identifiers are persisted; no credentials or message bodies are copied here. */
data class ForwardPlan(
    val accountId: String, val messageId: String, val uid: Long, val validity: Long,
    val remoteFolder: String, val files: List<ForwardFile>, val sourceKey: String = ""
) : Serializable {
    val ready get() = files.all { it.status == ForwardStatus.READY && it.prepared != null }
    val active get() = files.any { it.status == ForwardStatus.WAITING || it.status == ForwardStatus.DOWNLOADING }
    fun sourceMessage() = MailItem(messageId, accountId, uid, validity, remoteFolder,
        sender="", senderEmail="", recipients="", subject="", body="", date=0)
}

private fun ComposeDraft.needsInline() = (quote!=null && quoteIncluded) || bodyHtml?.contains("cid:",ignoreCase=true)==true
fun ComposeDraft.selectedForwardFiles() = forwarding?.files.orEmpty().filter{it.contentId.isBlank() || needsInline()}
fun ComposeDraft.resourcesReady() = selectedForwardFiles().all{it.status==ForwardStatus.READY && it.prepared!=null}
fun ComposeDraft.filesToSend() = (attachments + selectedForwardFiles().mapNotNull { it.prepared }).filter{it.contentId.isBlank() || needsInline()}

object ForwardRules {
    fun sourceKey(account: MailAccount)=listOf(account.imapHost.lowercase(),account.imapPort.toString(),account.imapStartTls.toString(),account.username).joinToString("|")
    fun plan(message: MailItem,account: MailAccount?=null,includeOrdinary: Boolean=true,restoreDraft: Boolean=false): ForwardPlan? {
        fun cid(value: String)=if(restoreDraft)message.composer?.resourceAliases?.get(value)?:value else value
        val remote = (if(message.uid>0)message.attachments else emptyList()).filter{a->message.outgoing.none{it.uri==a.path}}.filter{includeOrdinary || it.contentId.isNotBlank()}.mapIndexed { index, a ->
            ForwardFile("remote-$index", a.name, a.mime, a.size.toLong(), path=a.path,contentId=cid(a.contentId))
        }
        val local = message.outgoing.filter{includeOrdinary || it.contentId.isNotBlank()}.mapIndexed { index, a ->
            ForwardFile("local-$index", a.name, a.mime, a.size, sourceUri=a.uri,contentId=if(!restoreDraft && a.contentId.isNotBlank())message.composer?.resourceAliases?.entries?.firstOrNull{it.value==a.contentId}?.key?:a.contentId else cid(a.contentId))
        }
        return (remote + local).takeIf { it.isNotEmpty() }?.let {
            ForwardPlan(message.accountId, message.id, message.uid, message.validity, message.remoteFolder, it,account?.let(::sourceKey).orEmpty())
        }
    }
    /** Editor text and sender win; worker-owned progress is always taken from storage. */
    fun mergeEditor(editor: ComposeDraft, stored: ForwardPlan?) = editor.copy(forwarding=stored)
    fun sending(draft: ComposeDraft): ComposeDraft {
        require(draft.resourcesReady()) { "Вложения ещё не подготовлены." }
        return draft.copy(attachments=draft.filesToSend(), forwarding=null)
    }
}

/** Shared by concurrent transfers. Unknown or inaccurate server sizes cannot bypass limits. */
class ForwardBudget(initial: Map<String, Long> = emptyMap()) {
    private val lengths = initial.toMutableMap()
    @Synchronized fun progress(key: String, bytes: Long) {
        require(bytes <= 20L * 1024 * 1024) { "Вложение больше 20 МБ." }
        require(lengths.filterKeys { it != key }.values.sum() + bytes <= 25L * 1024 * 1024) {
            "Суммарный размер вложений больше 25 МБ. Удалите лишний файл."
        }
        lengths[key] = bytes
    }
    @Synchronized fun remove(key: String) { lengths.remove(key) }
    @Synchronized fun retain(keys: Set<String>) { lengths.keys.retainAll(keys) }
}
