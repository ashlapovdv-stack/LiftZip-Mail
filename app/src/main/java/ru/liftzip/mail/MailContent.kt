package ru.liftzip.mail

object MailContent {
    const val PREVIEW_LENGTH=240
    fun preview(message: MailItem): MailItem = if(message.previewOnly)message else message.copy(body=message.body.take(PREVIEW_LENGTH),previewOnly=true,cacheBytes=if(message.contentCached)message.body.toByteArray(Charsets.UTF_8).size.toLong()else message.cacheBytes)
    fun matches(message: MailItem,terms: List<String>): Boolean {
        if(terms.isEmpty())return true
        val text="${message.sender} ${message.senderEmail} ${message.subject} ${message.body}".lowercase()
        return terms.all{it in text}
    }
    fun forStorage(message: MailItem,storedBody: String?): MailItem = if(!message.previewOnly)message else
        message.copy(body=requireNotNull(storedBody){"Полное содержимое письма не найдено. Обновите почту."},previewOnly=false)
}
