package ru.liftzip.mail

import org.json.JSONObject

internal fun ServerFolder.json()=JSONObject().put("account",accountId).put("path",path).put("name",name).put("role",role.name)
    .put("total",total).put("unread",unread).put("validity",validity).put("older",hasOlder).put("checked",checkedAt)
    .put("syncing",syncing).put("all",syncAll).put("error",error)
internal fun folderFromJson(j: JSONObject)=ServerFolder(j.getString("account"),j.getString("path"),j.getString("name"),
    runCatching{FolderRole.valueOf(j.optString("role"))}.getOrDefault(FolderRole.OTHER),
    if(j.isNull("total"))null else j.getInt("total"),if(j.isNull("unread"))null else j.getInt("unread"),j.optLong("validity"),j.optBoolean("older",true),
    j.optLong("checked"),j.optBoolean("syncing"),j.optBoolean("all"),j.optString("error"))
internal fun MailAction.json()=JSONObject().put("id",id).put("account",accountId).put("message",messageId).put("kind",kind.name)
    .put("payload",payload).put("revision",revision).put("subject",subject).put("server",serverKey).put("unread",unread).put("starred",starred)
    .put("created",created).put("attempt",attempt).put("next",nextAttempt).put("status",status).put("error",error).put("lease",lease)
internal fun actionFromJson(j: JSONObject)=MailAction(j.getString("id"),j.getString("account"),j.getString("message"),ActionKind.valueOf(j.getString("kind")),j.getString("payload"),
    j.getLong("revision"),j.optString("subject"),j.optString("server"),if(j.isNull("unread"))null else j.getBoolean("unread"),if(j.isNull("starred"))null else j.getBoolean("starred"),
    j.getLong("created"),j.optInt("attempt"),j.optLong("next"),j.optString("status","pending"),j.optString("error"),j.optString("lease"))
