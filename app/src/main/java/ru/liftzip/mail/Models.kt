package ru.liftzip.mail

import java.util.UUID

enum class Provider(val label: String, val imap: String, val smtp: String, val mark: String) {
    REG("REG.RU", "mail.hosting.reg.ru", "mail.hosting.reg.ru", "R"),
    MAIL("Mail.ru", "imap.mail.ru", "smtp.mail.ru", "@"),
    YANDEX("Яндекс", "imap.yandex.ru", "smtp.yandex.ru", "Я"),
    GMAIL("Gmail", "imap.gmail.com", "smtp.gmail.com", "G"),
    CUSTOM("Свой сервер", "", "", "С")
}

data class MailAccount(
    val id: String = UUID.randomUUID().toString(), val name: String, val email: String,
    val username: String = email, val provider: Provider, val imapHost: String,
    val imapPort: Int = 993, val imapStartTls: Boolean = false,
    val smtpHost: String, val smtpPort: Int = 465, val smtpStartTls: Boolean = false,
    val secret: String = "", val senderName: String = name, val enabled: Boolean = true, val authKind: AuthKind = AuthKind.PASSWORD,
    val colorSlot: Int = -1
)

data class Attachment(val path: String, val name: String, val mime: String, val size: Int,val contentId: String="")
data class OutgoingFile(val uri: String, val name: String, val mime: String, val size: Long,val contentId: String="") : java.io.Serializable
data class OpenedAttachment(val uri: String, val name: String, val mime: String) : java.io.Serializable
data class InboxHistory(val total: Int? = null, val hasOlder: Boolean = true, val unread: Int? = null)
data class InboxPage(val messages: List<MailItem>, val history: InboxHistory, val validity: Long? = messages.firstOrNull()?.validity)
data class ContentBatch(val messages: List<MailItem>,val missingIds: Set<String>,val failures: Map<String,String> = emptyMap())
data class UnreadPage(val validity: Long,val messages: List<MailItem>,val total: Int)
data class MailItem(
    val id: String, val accountId: String, val uid: Long = 0, val validity: Long = 0,
    val remoteFolder: String = "INBOX", val sender: String, val senderEmail: String,
    val recipients: String, val subject: String, val body: String, val date: Long,
    val unread: Boolean = false, val starred: Boolean = false, val localFolder: String = "inbox",
    val attachments: List<Attachment> = emptyList(), val outgoing: List<OutgoingFile> = emptyList(),
    val delivery: String = "", val previewOnly: Boolean = false, val contentCached: Boolean = true,
    val forwarding: ForwardPlan? = null, val internetMessageId: String="", val sourceDraftId: String?=null, val editRevision: Long=0, val pendingDelete: Boolean=false, val cacheBytes: Long=0, val cachedAt: Long=0,
    val htmlBody: String="", val contentVersion: Int=0,
    val answered: Boolean=false, val forwarded: Boolean=false, val responseTo: String?=null, val responseKind: String="", val cc: String="", val bcc: String="",
    val headers: MessageHeaders?=null,val composer: ComposerSnapshot?=null
)

data class ComposeDraft(
    val id: String = UUID.randomUUID().toString(), val accountId: String,
    val to: String = "", val subject: String = "", val body: String = "",
    val attachments: List<OutgoingFile> = emptyList(), val previousDeliveryUncertain: Boolean = false,
    val forwarding: ForwardPlan? = null, val internetMessageId: String="",val sourceDraftId: String?=null,val timestamp: Long=0,
    val responseTo: String?=null,val responseKind: String="",
    val cc: String="",val bcc: String="",val quote: QuotedSource?=null,val quoteIncluded: Boolean=true
) : java.io.Serializable

enum class MailFolder(val label: String, val key: String) {
    INBOX("Входящие", "inbox"), UNREAD("Непрочитанные", "unread"), STARRED("Избранное", "starred"),
    SENT("Отправленные", "sent"), DRAFTS("Черновики", "drafts");
    val navigationLabel get()=when(this){UNREAD->"Непроч.";SENT->"Отправл.";else->label}
}

object MailRules {
    fun newUnread(old: List<MailItem>, incoming: List<MailItem>, initialized: Boolean): Int {
        if(!initialized)return 0
        if(old.isNotEmpty() && incoming.isNotEmpty() && old.first().validity!=incoming.first().validity)return 0
        val known=old.map{it.id}.toSet()
        return incoming.count{it.unread && it.id !in known}
    }
    fun recipients(text: String) = text.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty)
    fun validEmail(value: String) = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$").matches(value.trim())
    fun validateDraft(draft: ComposeDraft): String? = when {
        !draft.resourcesReady() -> "Дождитесь подготовки вложений или удалите недоступные файлы."
        (recipients(draft.to)+recipients(draft.cc)+recipients(draft.bcc)).isEmpty() || (recipients(draft.to)+recipients(draft.cc)+recipients(draft.bcc)).any { !validEmail(it) } -> "Проверьте адреса получателей. Разделяйте их запятой."
        draft.subject.isBlank() -> "Добавьте тему письма."
        draft.subject.any { it == '\r' || it == '\n' } -> "Тема письма должна быть одной строкой."
        draft.body.isBlank() && (draft.quote==null || !draft.quoteIncluded) -> "Добавьте текст письма."
        draft.filesToSend().any { it.size > 20 * 1024 * 1024 } -> "Максимальный размер файла — 20 МБ."
        draft.filesToSend().sumOf { it.size.coerceAtLeast(0) } > 25 * 1024 * 1024 -> "Суммарный размер вложений — не более 25 МБ."
        else -> null
    }
    fun validateAccount(account: MailAccount, password: String, passwordRequired: Boolean = true): String? = when {
        account.name.isBlank() -> "Укажите название ящика."
        account.senderName.any { it == '\r' || it == '\n' } -> "Имя отправителя должно быть одной строкой."
        !validEmail(account.email) -> "Проверьте адрес почты."
        account.username.isBlank() -> "Укажите логин."
        account.imapHost.isBlank() || account.smtpHost.isBlank() -> "Укажите адреса IMAP и SMTP."
        listOf(account.imapHost, account.smtpHost).any { it.contains('/') || it.contains(':') || it.any(Char::isWhitespace) } -> "Укажите имя сервера без https:// и номера порта."
        account.imapPort !in 1..65535 || account.smtpPort !in 1..65535 -> "Проверьте номера портов."
        account.authKind==AuthKind.GOOGLE && AuthRules.validateGoogle(account)!=null -> AuthRules.validateGoogle(account)
        passwordRequired && account.authKind==AuthKind.PASSWORD && password.isBlank() -> "Введите пароль почты или пароль приложения."
        else -> null
    }
    fun filter(messages: List<MailItem>, accountId: String?, folder: MailFolder, query: String): List<MailItem> {
        val terms = query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return FolderRules.display(messages).filter { m ->
            (accountId == null || m.accountId == accountId) &&
                (when(folder) {
                    MailFolder.STARRED -> m.starred && !FolderRules.draft(m) && m.localFolder!="outbox"
                    MailFolder.UNREAD -> FolderRules.unread(m)
                    MailFolder.SENT -> FolderRules.sent(m)
                    MailFolder.DRAFTS -> FolderRules.draft(m)
                    else -> m.localFolder == folder.key
                }) &&
                MailContent.matches(m,terms)
        }.sortedByDescending { it.date }.let{if(folder in setOf(MailFolder.SENT,MailFolder.DRAFTS,MailFolder.UNREAD,MailFolder.STARRED))FolderRules.deduplicate(it,folder==MailFolder.DRAFTS)else it}
    }
}

