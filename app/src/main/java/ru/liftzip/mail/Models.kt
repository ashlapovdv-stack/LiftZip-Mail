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
    val secret: String = "", val senderName: String = name, val enabled: Boolean = true
)

data class Attachment(val path: String, val name: String, val mime: String, val size: Int)
data class OutgoingFile(val uri: String, val name: String, val mime: String, val size: Long) : java.io.Serializable
data class OpenedAttachment(val uri: String, val name: String, val mime: String) : java.io.Serializable
data class InboxHistory(val total: Int? = null, val hasOlder: Boolean = true)
data class InboxPage(val messages: List<MailItem>, val history: InboxHistory, val validity: Long? = messages.firstOrNull()?.validity)
data class ContentBatch(val messages: List<MailItem>,val missingIds: Set<String>)
data class UnreadPage(val validity: Long,val messages: List<MailItem>,val total: Int)
data class MailItem(
    val id: String, val accountId: String, val uid: Long = 0, val validity: Long = 0,
    val remoteFolder: String = "INBOX", val sender: String, val senderEmail: String,
    val recipients: String, val subject: String, val body: String, val date: Long,
    val unread: Boolean = false, val starred: Boolean = false, val localFolder: String = "inbox",
    val attachments: List<Attachment> = emptyList(), val outgoing: List<OutgoingFile> = emptyList(),
    val delivery: String = "", val previewOnly: Boolean = false, val contentCached: Boolean = true
)

data class ComposeDraft(
    val id: String = UUID.randomUUID().toString(), val accountId: String,
    val to: String = "", val subject: String = "", val body: String = "",
    val attachments: List<OutgoingFile> = emptyList(), val previousDeliveryUncertain: Boolean = false
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
        recipients(draft.to).isEmpty() || recipients(draft.to).any { !validEmail(it) } -> "Проверьте адреса получателей. Разделяйте их запятой."
        draft.subject.isBlank() -> "Добавьте тему письма."
        draft.subject.any { it == '\r' || it == '\n' } -> "Тема письма должна быть одной строкой."
        draft.body.isBlank() -> "Добавьте текст письма."
        draft.attachments.any { it.size > 20 * 1024 * 1024 } -> "Максимальный размер файла — 20 МБ."
        draft.attachments.sumOf { it.size.coerceAtLeast(0) } > 25 * 1024 * 1024 -> "Суммарный размер вложений — не более 25 МБ."
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
        passwordRequired && password.isBlank() -> "Введите пароль почты или пароль приложения."
        else -> null
    }
    fun filter(messages: List<MailItem>, accountId: String?, folder: MailFolder, query: String): List<MailItem> {
        val terms = query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return messages.filter { m ->
            (accountId == null || m.accountId == accountId) &&
                (when(folder) {
                    MailFolder.STARRED -> m.starred && m.localFolder != "drafts"
                    MailFolder.UNREAD -> m.unread && m.localFolder != "drafts"
                    else -> m.localFolder == folder.key
                }) &&
                MailContent.matches(m,terms)
        }.sortedByDescending { it.date }
    }
}

