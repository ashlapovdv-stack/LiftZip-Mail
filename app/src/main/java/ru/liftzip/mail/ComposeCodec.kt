package ru.liftzip.mail

import org.json.JSONObject
import org.json.JSONArray

/** Shared versioned codec for SQLite/action snapshots and private server-draft metadata. */
object ComposeCodec {
    const val MIME="application/vnd.liftzip.draft+json"
    const val MAX_METADATA_BYTES=2*1024*1024
    fun headers(h: MessageHeaders)=JSONObject().put("messageId",h.messageId).put("replyTo",h.replyTo).put("inReplyTo",h.inReplyTo).put("references",JSONArray(h.references))
    fun headers(j: JSONObject)=MessageHeaders(j.optString("messageId"),j.optString("replyTo"),j.optString("inReplyTo"),j.optJSONArray("references")?.let{a->List(a.length()){a.getString(it)}}.orEmpty())
    fun encode(s: ComposerSnapshot)=JSONObject().put("version",1).put("text",s.text).put("bodyHtml",s.bodyHtml).put("included",s.quoteIncluded).put("cc",s.cc).put("bcc",s.bcc).put("aliases",JSONObject(s.resourceAliases)).put("responseTo",s.responseTo).put("responseKind",s.responseKind).put("to",s.to).put("subject",s.subject)
        .put("quote",s.quote?.let{q->JSONObject().put("account",q.accountId).put("message",q.messageId).put("uid",q.uid).put("validity",q.validity).put("folder",q.folder)
            .put("sender",q.sender).put("senderEmail",q.senderEmail).put("serverKey",q.serverKey).put("to",q.recipients).put("subject",q.subject).put("date",q.date).put("plain",q.plain).put("html",q.html).put("headers",q.headers?.let(::headers)).put("kind",q.kind)})
    fun decode(j: JSONObject): ComposerSnapshot? {
        if(j.optInt("version")!=1)return null
        return ComposerSnapshot(j.getString("text"),j.optJSONObject("quote")?.let{q->QuotedSource(q.optString("account"),q.optString("message"),q.optLong("uid"),q.optLong("validity"),q.optString("folder"),
            q.optString("sender"),q.optString("to"),q.optString("subject"),q.optLong("date"),q.optString("plain"),q.optString("html"),q.optJSONObject("headers")?.let(::headers),q.optString("kind","reply"),q.optString("senderEmail"),q.optString("serverKey"))},j.optBoolean("included",true),j.optString("cc"),j.optString("bcc"),j.optJSONObject("aliases")?.let{a->a.keys().asSequence().associateWith{a.getString(it)}}.orEmpty(),j.optString("responseTo").takeIf{it.isNotBlank() && it!="null"},j.optString("responseKind"),if(j.has("to"))j.getString("to")else null,if(j.has("subject"))j.getString("subject")else null,if(j.has("bodyHtml") && !j.isNull("bodyHtml"))ComposerHtml.clean(j.getString("bodyHtml"))else null)
    }
    fun snapshot(d: ComposeDraft)=ComposerSnapshot(d.bodyHtml?.let(ComposerHtml::plain)?:d.body,d.quote,d.quoteIncluded,d.cc,d.bcc,responseTo=d.responseTo,responseKind=d.responseKind,to=d.to,subject=d.subject,bodyHtml=d.bodyHtml)
    fun draftMetadata(d: ComposeDraft,inlineIds: Map<String,String> = emptyMap())=encode(snapshot(d).copy(resourceAliases=inlineIds.entries.associate{it.value to it.key})).put("draftId",d.id).toString()
    fun readMetadata(text: String,expectedDraftId: String?): ComposerSnapshot? = runCatching {
        if(expectedDraftId.isNullOrBlank() || text.toByteArray().size>MAX_METADATA_BYTES)return null
        val j=JSONObject(text)
        if(j.optString("draftId")!=expectedDraftId)return null
        decode(j)
    }.getOrNull()
}
