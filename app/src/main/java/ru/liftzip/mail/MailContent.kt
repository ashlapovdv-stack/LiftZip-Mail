package ru.liftzip.mail

object MailContent {
    const val PREVIEW_LENGTH=240
    const val FORMAT_VERSION=1
    private val imageLabels=Regex("""(?i)\[(?:image|изображение)\s*:\s*[^\]\r\n]{0,240}]""")
    private val whitespace=Regex("\\s+")
    /** Some senders put image alt markers in their text/plain alternative. Keep the source intact. */
    fun snippet(body: String)=imageLabels.replace(body,"").replace("\uFFFC","").replace(whitespace," ").trim()
    fun bytes(message: MailItem)=message.body.toByteArray(Charsets.UTF_8).size.toLong()+message.htmlBody.toByteArray(Charsets.UTF_8).size
    fun needsRenderUpdate(message: MailItem)=message.contentCached && FolderRules.remote(message) && message.contentVersion<FORMAT_VERSION
    fun preview(message: MailItem): MailItem = if(message.previewOnly)message.copy(htmlBody="")else message.copy(composer=null,body=message.body.take(PREVIEW_LENGTH),htmlBody="",previewOnly=true,cacheBytes=if(message.contentCached)bytes(message)else message.cacheBytes)
    fun matches(message: MailItem,terms: List<String>): Boolean {
        if(terms.isEmpty())return true
        val text="${message.sender} ${message.senderEmail} ${message.subject} ${message.body}".lowercase()
        return terms.all{it in text}
    }
    fun forStorage(message: MailItem,storedBody: String?,storedHtml: String?=null,storedVersion: Int?=null): MailItem = if(!message.previewOnly)message else
        message.copy(body=requireNotNull(storedBody){"Полное содержимое письма не найдено. Обновите почту."},htmlBody=storedHtml?:message.htmlBody,contentVersion=storedVersion?:message.contentVersion,previewOnly=false)
}
