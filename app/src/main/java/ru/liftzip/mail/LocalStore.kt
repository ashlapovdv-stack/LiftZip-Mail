package ru.liftzip.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) : SQLiteOpenHelper(context, "mail-v1.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, folder TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX message_account ON messages(account_id, folder)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun accounts(): List<MailAccount> = readableDatabase.rawQuery("SELECT data FROM accounts ORDER BY rowid", null).use { c ->
        buildList { while(c.moveToNext()) { val j=JSONObject(c.getString(0)); add(MailAccount(
            id=j.getString("id"), name=j.getString("name"), email=j.getString("email"), username=j.getString("username"),
            provider=Provider.valueOf(j.getString("provider")), imapHost=j.getString("imapHost"), imapPort=j.getInt("imapPort"),
            imapStartTls=j.getBoolean("imapStartTls"), smtpHost=j.getString("smtpHost"), smtpPort=j.getInt("smtpPort"),
            smtpStartTls=j.getBoolean("smtpStartTls"), secret=j.getString("secret"), senderName=j.optString("senderName",j.getString("name"))
        )) } }
    }
    @Synchronized fun putAccount(a: MailAccount) {
        val j=JSONObject().put("id",a.id).put("name",a.name).put("email",a.email).put("username",a.username)
            .put("provider",a.provider.name).put("imapHost",a.imapHost).put("imapPort",a.imapPort).put("imapStartTls",a.imapStartTls)
            .put("smtpHost",a.smtpHost).put("smtpPort",a.smtpPort).put("smtpStartTls",a.smtpStartTls).put("secret",a.secret).put("senderName",a.senderName)
        writableDatabase.insertOrThrow("accounts",null,ContentValues().apply { put("id",a.id);put("data",j.toString()) })
    }
    @Synchronized fun updateAccount(a: MailAccount,resetInbox: Boolean) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val j=JSONObject().put("id",a.id).put("name",a.name).put("email",a.email).put("username",a.username)
                .put("provider",a.provider.name).put("imapHost",a.imapHost).put("imapPort",a.imapPort).put("imapStartTls",a.imapStartTls)
                .put("smtpHost",a.smtpHost).put("smtpPort",a.smtpPort).put("smtpStartTls",a.smtpStartTls).put("secret",a.secret).put("senderName",a.senderName)
            // UPDATE preserves the account row and its drafts/sent; REPLACE would cascade-delete them.
            require(db.update("accounts",ContentValues().apply{put("data",j.toString())},"id = ?",arrayOf(a.id))==1)
            if(resetInbox)db.delete("messages","account_id = ? AND folder IN (?, ?)",arrayOf(a.id,"inbox","unread-cache"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun removeAccount(id: String) { writableDatabase.delete("accounts","id = ?",arrayOf(id)) }
    @Synchronized fun messages(): List<MailItem> = readableDatabase.rawQuery("SELECT data FROM messages",null).use { c -> buildList {
        while(c.moveToNext()) add(decodeMessage(JSONObject(c.getString(0)),preview=true))
    } }
    @Synchronized fun message(id: String): MailItem?=readableDatabase.rawQuery("SELECT data FROM messages WHERE id = ?",arrayOf(id)).use{c->
        if(c.moveToFirst())decodeMessage(JSONObject(c.getString(0)))else null
    }
    @Synchronized fun search(query: String,checkCancelled: ()->Unit): Set<String> {
        val terms=query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if(terms.isEmpty())return emptySet()
        return readableDatabase.rawQuery("SELECT data FROM messages",null).use{c->buildSet{
            while(c.moveToNext()){
                checkCancelled()
                val m=decodeMessage(JSONObject(c.getString(0)))
                if(MailContent.matches(m,terms))add(m.id)
            }
        }}
    }
    @Synchronized fun putMessage(m: MailItem) { putMessage(writableDatabase,m) }
    private fun putMessage(db: SQLiteDatabase,m: MailItem) {
        // A flag/header update from a list preview must never overwrite the stored full body.
        val storedBody=if(m.previewOnly)db.rawQuery("SELECT data FROM messages WHERE id = ?",arrayOf(m.id)).use{c->if(c.moveToFirst())JSONObject(c.getString(0)).getString("body") else null}else null
        val full=MailContent.forStorage(m,storedBody)
        db.insertWithOnConflict("messages",null,ContentValues().apply {
            put("id",full.id); put("account_id",full.accountId); put("folder",full.localFolder); put("data",encodeMessage(full).toString())
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun removeMessage(id: String) { writableDatabase.delete("messages","id = ?",arrayOf(id)) }
    @Synchronized fun replaceInbox(accountId: String,items: List<MailItem>) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages","account_id = ? AND folder = ?",arrayOf(accountId,"inbox"))
            items.forEach { putMessage(db,it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun applyInbox(previous: List<MailItem>,items: List<MailItem>) {
        val db=writableDatabase
        val old=previous.associateBy{it.id}
        val ids=items.map{it.id}.toSet()
        db.beginTransaction()
        try {
            previous.filter{it.id !in ids}.forEach{db.delete("messages","id = ?",arrayOf(it.id))}
            items.filter{old[it.id]!=it}.forEach{putMessage(db,it)}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun putMessages(items: List<MailItem>) {
        val db=writableDatabase
        db.beginTransaction()
        try {items.forEach{putMessage(db,it)};db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    private fun encodeMessage(m: MailItem) = JSONObject().put("id",m.id).put("account",m.accountId).put("uid",m.uid)
        .put("validity",m.validity).put("remoteFolder",m.remoteFolder).put("sender",m.sender).put("email",m.senderEmail)
        .put("to",m.recipients).put("subject",m.subject).put("body",m.body).put("date",m.date).put("unread",m.unread)
        .put("starred",m.starred).put("folder",m.localFolder).put("delivery",m.delivery).put("contentCached",m.contentCached)
        .put("attachments",JSONArray().apply { m.attachments.forEach { a->put(JSONObject().put("path",a.path).put("name",a.name).put("mime",a.mime).put("size",a.size)) } })
        .put("outgoing",JSONArray().apply { m.outgoing.forEach { a->put(JSONObject().put("uri",a.uri).put("name",a.name).put("mime",a.mime).put("size",a.size)) } })
    private fun decodeMessage(j: JSONObject,preview: Boolean=false): MailItem {
        val attachments=j.optJSONArray("attachments")?:JSONArray()
        val outgoing=j.optJSONArray("outgoing")?:JSONArray()
        return MailItem(j.getString("id"),j.getString("account"),j.getLong("uid"),j.getLong("validity"),j.getString("remoteFolder"),
            HeaderText.decode(j.getString("sender")),j.getString("email"),HeaderText.recipients(j.getString("to")),HeaderText.decode(j.getString("subject")),j.getString("body").let{if(preview)it.take(MailContent.PREVIEW_LENGTH)else it},j.getLong("date"),
            j.getBoolean("unread"),j.getBoolean("starred"),j.getString("folder"),
            List(attachments.length()) { i-> val a=attachments.getJSONObject(i);Attachment(a.getString("path"),a.getString("name"),a.getString("mime"),a.getInt("size")) },
            List(outgoing.length()) { i-> val a=outgoing.getJSONObject(i);OutgoingFile(a.getString("uri"),a.getString("name"),a.getString("mime"),a.getLong("size")) },j.optString("delivery"),previewOnly=preview,contentCached=j.optBoolean("contentCached",true))
    }
}
