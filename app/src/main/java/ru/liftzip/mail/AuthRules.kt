package ru.liftzip.mail

enum class AuthKind { PASSWORD, GOOGLE }

object AuthRules {
    fun validateGoogle(a: MailAccount): String?=when {
        a.provider!=Provider.GMAIL || !a.imapHost.equals("imap.gmail.com",true) || !a.smtpHost.equals("smtp.gmail.com",true)->"Вход Google доступен только для серверов Gmail. Для своего сервера используйте пароль."
        !a.username.equals(a.email,true)->"Для входа Google логин должен совпадать с адресом почты."
        a.imapPort!=993 || a.imapStartTls || !(a.smtpPort==465 && !a.smtpStartTls || a.smtpPort==587 && a.smtpStartTls)->"Для Google используйте защищённые порты Gmail: IMAP 993, SMTP 465 или 587 с STARTTLS."
        else->null
    }
    fun serverKey(a: MailAccount)=listOf(a.imapHost.lowercase(),a.imapPort.toString(),a.imapStartTls.toString(),a.username).joinToString("|")
}
