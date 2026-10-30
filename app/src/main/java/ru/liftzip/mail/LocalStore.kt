package ru.liftzip.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) : SQLiteOpenHelper(context, "mail-v1.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, folder TEXT NOT NULL, data TEXT NOT NULL, preview TEXT)")
        db.execSQL("CREATE INDEX message_account ON messages(account_id, folder)")
        createSyncTables(db)
    }
    private fun createSyncTables(db: SQLiteDatabase) {
        val hasPreview=db.rawQuery("PRAGMA table_info(messages)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(c.getColumnIndexOrThrow("name"))=="preview")found=true;found}
        if(!hasPreview)db.execSQL("ALTER TABLE messages ADD COLUMN preview TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS server_folders (account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, path TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(account_id,path))")
        db.execSQL("CREATE TABLE IF NOT EXISTS mail_actions (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, revision INTEGER NOT NULL, data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {if(oldVersion<2)createSyncTables(db)}
    @Synchronized fun folders(): List<ServerFolder> = readableDatabase.rawQuery("SELECT data FROM server_folders",null).use{c->buildList{while(c.moveToNext())add(folderFromJson(JSONObject(c.getString(0))))}}
    @Synchronized fun putFolders(items: List<ServerFolder>) {
        val db=writableDatabase;db.beginTransaction()
        try{items.forEach{f->db.insertWithOnConflict("server_folders",null,ContentValues().apply{put("account_id",f.accountId);put("path",f.path);put("data",f.json().toString())},SQLiteDatabase.CONFLICT_REPLACE)};db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    @Synchronized fun replaceFolders(accountId: String,items: List<ServerFolder>) {
        val db=writableDatabase;db.beginTransaction()
        try{db.delete("server_folders","account_id=?",arrayOf(accountId));putFolders(items);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    @Synchronized fun compact(){writableDatabase.execSQL("VACUUM")}
    @Synchronized fun actions(): List<MailAction> = readableDatabase.rawQuery("SELECT data FROM mail_actions ORDER BY rowid",null).use{c->buildList{while(c.moveToNext())add(actionFromJson(JSONObject(c.getString(0))))}}
    @Synchronized fun action(id: String): MailAction? = readableDatabase.rawQuery("SELECT data FROM mail_actions WHERE id=?",arrayOf(id)).use{c->if(c.moveToFirst())actionFromJson(JSONObject(c.getString(0)))else null}
    private fun putAction(db: SQLiteDatabase,a: MailAction) {db.insertWithOnConflict("mail_actions",null,ContentValues().apply{put("id",a.id);put("account_id",a.accountId);put("revision",a.revision);put("data",a.json().toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    @Synchronized fun putAction(a: MailAction)=putAction(writableDatabase,a)
    @Synchronized fun replaceAction(expected: MailAction,updated: MailAction): Boolean=writableDatabase.update("mail_actions",ContentValues().apply{put("revision",updated.revision);put("data",updated.json().toString())},"id=? AND revision=?",arrayOf(expected.id,expected.revision.toString()))==1
    @Synchronized fun completeAction(a: MailAction)=writableDatabase.delete("mail_actions","id=? AND revision=?",arrayOf(a.id,a.revision.toString()))==1
    @Synchronized fun applyActions(items: List<MailItem>,remove: Set<String> = emptySet(),actions: List<MailAction> = emptyList(),removeActions: Set<String> = emptySet()) {
        val db=writableDatabase;db.beginTransaction()
        try{items.forEach{putMessage(db,it)};remove.forEach{db.delete("messages","id=?",arrayOf(it))};actions.forEach{putAction(db,it)};removeActions.forEach{db.delete("mail_actions","id=?",arrayOf(it))};db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    @Synchronized fun finishAction(action: MailAction,items: List<MailItem> = emptyList(),remove: Set<String> = emptySet(),enqueue: List<MailAction> = emptyList()): Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            if(db.delete("mail_actions","id=? AND revision=?",arrayOf(action.id,action.revision.toString()))!=1)return false
            items.forEach{putMessage(db,it)};remove.forEach{db.delete("messages","id=?",arrayOf(it))};enqueue.forEach{putAction(db,it)}
            db.setTransactionSuccessful();return true
        }finally{db.endTransaction()}
    }
    @Synchronized fun accounts(): List<MailAccount> = readableDatabase.rawQuery("SELECT data FROM accounts ORDER BY rowid", null).use { c ->
        buildList { while(c.moveToNext()) { val j=JSONObject(c.getString(0)); add(MailAccount(
            id=j.getString("id"), name=j.getString("name"), email=j.getString("email"), username=j.getString("username"),
            provider=Provider.valueOf(j.getString("provider")), imapHost=j.getString("imapHost"), imapPort=j.getInt("imapPort"),
            imapStartTls=j.getBoolean("imapStartTls"), smtpHost=j.getString("smtpHost"), smtpPort=j.getInt("smtpPort"),
            smtpStartTls=j.getBoolean("smtpStartTls"), secret=j.getString("secret"), senderName=j.optString("senderName",j.getString("name")),enabled=j.optBoolean("enabled",true),authKind=runCatching{AuthKind.valueOf(j.optString("authKind","PASSWORD"))}.getOrDefault(AuthKind.PASSWORD)
        )) } }
    }
    @Synchronized fun putAccount(a: MailAccount) {
        val j=JSONObject().put("id",a.id).put("name",a.name).put("email",a.email).put("username",a.username)
            .put("provider",a.provider.name).put("imapHost",a.imapHost).put("imapPort",a.imapPort).put("imapStartTls",a.imapStartTls)
            .put("smtpHost",a.smtpHost).put("smtpPort",a.smtpPort).put("smtpStartTls",a.smtpStartTls).put("secret",a.secret).put("senderName",a.senderName).put("enabled",a.enabled).put("authKind",a.authKind.name)
        writableDatabase.insertOrThrow("accounts",null,ContentValues().apply { put("id",a.id);put("data",j.toString()) })
    }
    @Synchronized fun updateAccount(a: MailAccount,resetInbox: Boolean) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val j=JSONObject().put("id",a.id).put("name",a.name).put("email",a.email).put("username",a.username)
                .put("provider",a.provider.name).put("imapHost",a.imapHost).put("imapPort",a.imapPort).put("imapStartTls",a.imapStartTls)
                .put("smtpHost",a.smtpHost).put("smtpPort",a.smtpPort).put("smtpStartTls",a.smtpStartTls).put("secret",a.secret).put("senderName",a.senderName).put("enabled",a.enabled).put("authKind",a.authKind.name)
            // UPDATE preserves the account row and its drafts/sent; REPLACE would cascade-delete them.
            require(db.update("accounts",ContentValues().apply{put("data",j.toString())},"id = ?",arrayOf(a.id))==1)
            if(resetInbox) {
                db.delete("messages","account_id = ? AND (folder IN (?, ?) OR folder LIKE ?)",arrayOf(a.id,"inbox","unread-cache","remote-%"))
                db.delete("server_folders","account_id=?",arrayOf(a.id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun removeAccount(id: String) { writableDatabase.delete("accounts","id = ?",arrayOf(id)) }
    @Synchronized fun messages(): List<MailItem> {
        val backfill=mutableListOf<Pair<String,String>>()
        val items=readableDatabase.rawQuery("SELECT id,COALESCE(preview,data),preview IS NULL FROM messages",null).use{c->buildList{
            while(c.moveToNext()) {
                val m=decodeMessage(JSONObject(c.getString(1)),preview=true);add(m)
                if(c.getInt(2)!=0)backfill+=m.id to encodeMessage(m).toString()
            }
        }}
        if(backfill.isNotEmpty()) {
            val db=writableDatabase;db.beginTransaction()
            try{backfill.forEach{(id,json)->db.update("messages",ContentValues().apply{put("preview",json)},"id=? AND preview IS NULL",arrayOf(id))};db.setTransactionSuccessful()}finally{db.endTransaction()}
        }
        return items
    }
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
        val full=MailContent.forStorage(m,storedBody).let{it.copy(cacheBytes=if(it.contentCached)it.body.toByteArray(Charsets.UTF_8).size.toLong()else it.cacheBytes,cachedAt=it.cachedAt.takeIf{time->time>0}?:System.currentTimeMillis())}
        db.insertWithOnConflict("messages",null,ContentValues().apply {
            put("id",full.id); put("account_id",full.accountId); put("folder",full.localFolder); put("data",encodeMessage(full).toString());put("preview",encodeMessage(full.copy(body=full.body.take(MailContent.PREVIEW_LENGTH),previewOnly=true)).toString())
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun removeMessage(id: String) { writableDatabase.delete("messages","id = ?",arrayOf(id)) }
    @Synchronized fun removeMessages(ids: Set<String>) {
        val db=writableDatabase;db.beginTransaction()
        try{ids.forEach{db.delete("messages","id = ?",arrayOf(it))};db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
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
    internal fun encodeMessage(m: MailItem) = JSONObject().put("id",m.id).put("account",m.accountId).put("uid",m.uid)
        .put("validity",m.validity).put("remoteFolder",m.remoteFolder).put("sender",m.sender).put("email",m.senderEmail)
        .put("to",m.recipients).put("subject",m.subject).put("body",m.body).put("date",m.date).put("unread",m.unread)
        .put("starred",m.starred).put("folder",m.localFolder).put("delivery",m.delivery).put("contentCached",m.contentCached)
        .put("cacheBytes",m.cacheBytes).put("cachedAt",m.cachedAt).put("internetMessageId",m.internetMessageId).put("sourceDraftId",m.sourceDraftId).put("editRevision",m.editRevision).put("pendingDelete",m.pendingDelete)
        .put("forwarding",m.forwarding?.let(::encodeForward))
        .put("attachments",JSONArray().apply { m.attachments.forEach { a->put(JSONObject().put("path",a.path).put("name",a.name).put("mime",a.mime).put("size",a.size)) } })
        .put("outgoing",JSONArray().apply { m.outgoing.forEach { a->put(JSONObject().put("uri",a.uri).put("name",a.name).put("mime",a.mime).put("size",a.size)) } })
    internal fun decodeMessage(j: JSONObject,preview: Boolean=false): MailItem {
        val attachments=j.optJSONArray("attachments")?:JSONArray()
        val outgoing=j.optJSONArray("outgoing")?:JSONArray()
        return MailItem(j.getString("id"),j.getString("account"),j.getLong("uid"),j.getLong("validity"),j.getString("remoteFolder"),
            HeaderText.decode(j.getString("sender")),j.getString("email"),HeaderText.recipients(j.getString("to")),HeaderText.decode(j.getString("subject")),j.getString("body").let{if(preview)it.take(MailContent.PREVIEW_LENGTH)else it},j.getLong("date"),
            j.getBoolean("unread"),j.getBoolean("starred"),j.getString("folder"),
            List(attachments.length()) { i-> val a=attachments.getJSONObject(i);Attachment(a.getString("path"),a.getString("name"),a.getString("mime"),a.getInt("size")) },
            List(outgoing.length()) { i-> val a=outgoing.getJSONObject(i);OutgoingFile(a.getString("uri"),a.getString("name"),a.getString("mime"),a.getLong("size")) },j.optString("delivery"),previewOnly=preview,contentCached=j.optBoolean("contentCached",true),forwarding=j.optJSONObject("forwarding")?.let(::decodeForward),internetMessageId=j.optString("internetMessageId").ifBlank{if(j.getString("folder") in setOf("drafts","sent","outbox"))MessageIdentity.forDraft(j.getString("id"))else ""},sourceDraftId=j.optString("sourceDraftId").takeIf{it.isNotBlank() && it!="null"},editRevision=j.optLong("editRevision"),pendingDelete=j.optBoolean("pendingDelete"),cacheBytes=if(j.has("cacheBytes"))j.optLong("cacheBytes")else j.getString("body").length*2L,cachedAt=j.optLong("cachedAt",j.optLong("date")))
    }
    private fun encodeForward(p: ForwardPlan)=JSONObject().put("account",p.accountId).put("message",p.messageId)
        .put("uid",p.uid).put("validity",p.validity).put("folder",p.remoteFolder).put("sourceKey",p.sourceKey)
        .put("files",JSONArray().apply{p.files.forEach{f->put(JSONObject().put("key",f.key).put("name",f.name).put("mime",f.mime).put("size",f.size)
            .put("path",f.path).put("sourceUri",f.sourceUri).put("status",f.status.name).put("bytes",f.bytes).put("error",f.error)
            .put("prepared",f.prepared?.let{JSONObject().put("uri",it.uri).put("name",it.name).put("mime",it.mime).put("size",it.size)}))}})
    private fun decodeForward(j: JSONObject): ForwardPlan {
        val files=j.getJSONArray("files")
        return ForwardPlan(j.getString("account"),j.getString("message"),j.getLong("uid"),j.getLong("validity"),j.getString("folder"),
            List(files.length()){i->val f=files.getJSONObject(i)
                ForwardFile(f.getString("key"),f.getString("name"),f.getString("mime"),f.getLong("size"),
                    f.optString("path").takeIf{it.isNotEmpty()},f.optString("sourceUri").takeIf{it.isNotEmpty()},
                    runCatching{ForwardStatus.valueOf(f.optString("status"))}.getOrDefault(ForwardStatus.WAITING),f.optLong("bytes"),
                    f.optJSONObject("prepared")?.let{OutgoingFile(it.getString("uri"),it.getString("name"),it.getString("mime"),it.getLong("size"))},f.optString("error"))},j.optString("sourceKey"))
    }
}
