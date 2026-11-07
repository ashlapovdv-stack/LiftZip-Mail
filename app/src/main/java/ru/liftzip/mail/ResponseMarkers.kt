package ru.liftzip.mail

/** An icon is added only after SMTP has accepted the reply or forward. */
object ResponseMarkers {
    fun accepted(original: MailItem?,kind: String): MailItem? = when(kind) {
        "reply" -> original?.copy(answered=true)
        "forward" -> original?.copy(forwarded=true)
        else -> null
    }
    fun serverAction(original: MailItem,kind: String): ActionKind? {
        if(!FolderRules.remote(original) || original.pendingDelete)return null
        return when(kind){"reply"->ActionKind.MARK_ANSWERED;"forward"->ActionKind.MARK_FORWARDED;else->null}
    }
}
