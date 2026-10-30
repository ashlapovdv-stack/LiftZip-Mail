package ru.liftzip.mail

import java.util.Base64

enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, OTHER }
data class ServerFolder(val accountId: String,val path: String,val name: String,val role: FolderRole=FolderRole.OTHER,
    val total: Int?=null,val unread: Int?=null,val validity: Long=0,val hasOlder: Boolean=true,val checkedAt: Long=0,
    val syncing: Boolean=false,val syncAll: Boolean=false,val error: String="") {
    val title get()=when(role){FolderRole.INBOX->"Входящие";FolderRole.SENT->"Отправленные";FolderRole.DRAFTS->"Черновики";FolderRole.TRASH->"Корзина";FolderRole.JUNK->"Спам";FolderRole.ARCHIVE->"Архив";else->name}
}

object FolderRules {
    fun role(path: String,attributes: Collection<String>): FolderRole {
        if(path.equals("INBOX",true))return FolderRole.INBOX
        val attrs=attributes.map{it.lowercase()}.toSet()
        listOf("\\sent" to FolderRole.SENT,"\\drafts" to FolderRole.DRAFTS,"\\trash" to FolderRole.TRASH,"\\junk" to FolderRole.JUNK,"\\archive" to FolderRole.ARCHIVE,"\\all" to FolderRole.ARCHIVE).firstOrNull{it.first in attrs}?.let{return it.second}
        return when(path.substringAfterLast('/').substringAfterLast('.').lowercase()) {
            "sent","sent mail","sent items","sent messages","отправленные"->FolderRole.SENT
            "drafts","черновики"->FolderRole.DRAFTS
            "trash","deleted items","deleted messages","корзина","удаленные","удалённые"->FolderRole.TRASH
            "spam","junk","спам"->FolderRole.JUNK
            "archive","archives","архив"->FolderRole.ARCHIVE
            else->FolderRole.OTHER
        }
    }
    fun local(role: FolderRole)=if(role==FolderRole.INBOX)"inbox" else "remote-"+role.name.lowercase()
    fun id(accountId: String,path: String,validity: Long,uid: Long)=if(path.equals("INBOX",true))"$accountId:$validity:$uid" else
        "$accountId:folder:"+Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))+":$validity:$uid"
    fun remote(m: MailItem)=m.uid>0 && m.validity>0 && (SyncRules.remoteInbox(m) || m.localFolder.startsWith("remote-"))
    fun sent(m: MailItem)=m.localFolder in setOf("sent","remote-sent")
    fun draft(m: MailItem)=m.localFolder in setOf("drafts","remote-drafts")
    fun unread(m: MailItem)=m.unread && !m.pendingDelete && m.localFolder !in setOf("drafts","remote-drafts","sent","remote-sent","remote-trash","remote-junk","outbox")
    fun deduplicate(items: List<MailItem>,preferDraft: Boolean=false): List<MailItem> = items.groupBy{it.accountId+"|"+it.internetMessageId.trim().ifBlank{it.id}}.values.map{group->
        group.minWith(compareBy<MailItem>{if(preferDraft && it.localFolder=="drafts")0 else 1}.thenBy{if(SyncRules.remoteInbox(it))0 else 1}.thenBy{if(it.uid>0)0 else 1}.thenBy{if(it.contentCached)0 else 1})
    }.sortedByDescending{it.date}
    fun display(messages: List<MailItem>): List<MailItem> {
        val edited=messages.filter{it.localFolder=="drafts"}.mapNotNull{it.sourceDraftId}.toSet()
        return messages.filter{!it.pendingDelete && it.id !in edited}
    }
}
