package ru.liftzip.mail

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Null messageId means the group of unread messages, never an arbitrary message. */
data class NotificationDestination(val messageId: String?=null)
data class NotificationOpenRequest(val destination: NotificationDestination,val token: String=UUID.randomUUID().toString())

object NotificationNavigation {
    fun uri(messageId: String?=null): String = if(messageId==null)"liftzip-mail://open/unread" else
        "liftzip-mail://open/message/"+URLEncoder.encode(messageId,"UTF-8").replace("+","%20")

    fun destination(data: String?,legacyId: String?=null,legacyGroup: Boolean=false): NotificationDestination? {
        if(data!=null) {
            val parsed=runCatching {
                val uri=URI(data)
                if(uri.scheme!="liftzip-mail" || uri.host!="open" || uri.rawQuery!=null || uri.rawFragment!=null)return@runCatching null
                val path=uri.rawPath?:return@runCatching null
                when {
                    path=="/unread" || path=="/inbox" -> NotificationDestination()
                    path.startsWith("/message/") -> URLDecoder.decode(path.removePrefix("/message/"),"UTF-8").takeIf{it.isNotBlank()}?.let(::NotificationDestination)
                    path.startsWith("/") && path.length>1 && '/' !in path.drop(1) -> URLDecoder.decode(path.drop(1),"UTF-8").let(::NotificationDestination)
                    else -> null
                }
            }.getOrNull()
            // The URI identifies the PendingIntent; extras may have been updated independently.
            return parsed
        }
        return legacyId?.takeIf{it.isNotBlank()}?.let(::NotificationDestination)?:if(legacyGroup)NotificationDestination()else null
    }

    fun consume(current: NotificationOpenRequest?,token: String): NotificationOpenRequest? = current?.takeUnless{it.token==token}
}
