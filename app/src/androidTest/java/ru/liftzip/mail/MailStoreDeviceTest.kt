package ru.liftzip.mail

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailStoreDeviceTest {
    private fun context(): Context {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"db-test-"+UUID.randomUUID()).apply{mkdirs()}
        return object: ContextWrapper(base) {
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences(root.name+"-"+name,mode)
            override fun getDatabasePath(name: String)=File(root,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun deleteDatabase(name: String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
    }
    private fun account()=MailAccount(id="a",name="Test",email="a@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com",enabled=false)
    private fun message(account: String="a")=MailItem("m",account,sender="S",senderEmail="s@example.com",recipients="r@example.com",subject="Test",body="Keep this complete body",date=1,localFolder="drafts")
    @Test fun migrationAddsSyncTablesWithoutDeletingExistingMail() {
        val c=context();val db=c.openOrCreateDatabase("legacy",0,null)
        try {
            db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,account_id TEXT,folder TEXT,data TEXT)")
            db.execSQL("INSERT INTO accounts VALUES('a','{}')");db.execSQL("INSERT INTO messages VALUES('m','a','drafts','original')")
            val helper=LocalStore(c);helper.onUpgrade(db,1,2);helper.onUpgrade(db,1,2)
            db.rawQuery("SELECT data FROM messages WHERE id='m'",null).use{assertTrue(it.moveToFirst());assertEquals("original",it.getString(0))}
            db.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('server_folders','mail_actions')",null).use{assertEquals(2,it.count)}
            helper.close()
        }finally{db.close();c.deleteDatabase("legacy")}
    }
    @Test fun oldAcknowledgementCannotDeleteANewerQueuedRevision() {
        val c=context();val db=LocalStore(c)
        try {
            db.putAccount(account());db.putMessage(message())
            val first=MailAction("flags","a","m",ActionKind.FLAGS,"{}",revision=1)
            db.putAction(first);db.putAction(first.copy(revision=2,starred=true))
            assertFalse(db.finishAction(first,remove=setOf("m")))
            assertNotNull(db.message("m"));assertEquals(2L,db.action("flags")?.revision)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun aFailedMessageWriteRollsBackTheActionAcknowledgement() {
        val c=context();val db=LocalStore(c)
        try {
            db.putAccount(account());db.putMessage(message())
            val a=MailAction("send","a","m",ActionKind.SEND,"{}")
            db.putAction(a)
            try{db.finishAction(a,items=listOf(message("missing-account")));fail("expected foreign key failure")}
            catch(_: SQLiteException){}
            assertNotNull(db.action(a.id));assertEquals("a",db.message("m")?.accountId)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun openingARealVersionOneDatabaseMigratesAndKeepsTheCompleteBody() {
        val c=context();val helper=LocalStore(c)
        val legacy=c.openOrCreateDatabase("mail-v1.db",0,null)
        try {
            legacy.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,data TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,folder TEXT NOT NULL,data TEXT NOT NULL)")
            val a=org.json.JSONObject().put("id","a").put("name","Test").put("email","a@example.com").put("username","a@example.com").put("provider","CUSTOM").put("imapHost","imap.example.com").put("imapPort",993).put("imapStartTls",false).put("smtpHost","smtp.example.com").put("smtpPort",465).put("smtpStartTls",false).put("secret","encrypted-old-secret")
            legacy.execSQL("INSERT INTO accounts VALUES(?,?)",arrayOf("a",a.toString()))
            val m=message().copy(body="Complete old body. "+"long text ".repeat(100))
            legacy.execSQL("INSERT INTO messages VALUES(?,?,?,?)",arrayOf(m.id,m.accountId,m.localFolder,helper.encodeMessage(m).toString()))
            legacy.version=1;legacy.close()
            assertEquals(2,helper.readableDatabase.version)
            assertEquals("encrypted-old-secret",helper.accounts().single().secret)
            assertEquals(m.body,helper.message(m.id)?.body)
            assertTrue(helper.messages().single().body.length<=MailContent.PREVIEW_LENGTH)
            assertEquals(m.body,helper.message(m.id)?.body)
            assertTrue(helper.actions().isEmpty());assertTrue(helper.folders().isEmpty())
        }finally{if(legacy.isOpen)legacy.close();helper.close();c.deleteDatabase("mail-v1.db")}
    }

    @Test fun htmlAndInlineImageMetadataSurviveAFlagUpdateFromAListPreview() {
        val c=context();val db=LocalStore(c)
        try {
            db.putAccount(account())
            val full=message().copy(body="Complete body "+"word ".repeat(200),htmlBody="<style>p{margin:0}</style><p>Complete body</p><img src='cid:x'>",contentVersion=1,
                attachments=listOf(Attachment("1","image.png","image/png",123,"x")))
            db.putMessage(full)
            val preview=db.messages().single()
            assertEquals("",preview.htmlBody)
            db.putMessage(preview.copy(unread=true,starred=true))
            val saved=db.message(full.id)!!
            assertEquals(full.body,saved.body);assertEquals(full.htmlBody,saved.htmlBody);assertEquals(1,saved.contentVersion)
            assertEquals("x",saved.attachments.single().contentId);assertEquals(MailContent.bytes(full),saved.cacheBytes)
            assertTrue(saved.unread);assertTrue(saved.starred)
            val old=db.encodeMessage(full).apply{remove("htmlBody");remove("contentVersion");getJSONArray("attachments").getJSONObject(0).remove("contentId")}
            val legacy=db.decodeMessage(old)
            assertEquals("",legacy.htmlBody);assertEquals(0,legacy.contentVersion);assertEquals("",legacy.attachments.single().contentId)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun spamAndRestoreQueuesSurviveReopeningTheDatabaseAndCanBeCancelled() {
        val c=context();var db=LocalStore(c)
        try {
            db.putAccount(account())
            val original=message().copy(uid=12,validity=7,localFolder="inbox")
            for(kind in listOf(ActionKind.SPAM,ActionKind.RESTORE_INBOX)) {
                db.putMessage(original)
                val action=MailAction("move","a",original.id,kind,db.encodeMessage(original).toString())
                db.applyActions(listOf(original.copy(pendingDelete=true)),actions=listOf(action))
                db.close();db=LocalStore(c)
                assertEquals(kind,db.actions().single().kind)
                assertTrue(ActionRules.overlay(db.messages(),db.actions()).single().pendingDelete)
                assertEquals(original.body,db.message(original.id)?.body)
                assertTrue(db.finishAction(db.actions().single(),items=listOf(original)))
                assertTrue(db.actions().isEmpty());assertFalse(db.message(original.id)!!.pendingDelete)
            }
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun cancellingAnOfflineSpamMoveRestoresTheMessageAndBothInboxCounters()=kotlinx.coroutines.runBlocking {
        val c=context();val db=LocalStore(c)
        try {
            db.putAccount(account())
            val original=message().copy(uid=12,validity=7,localFolder="inbox",unread=true)
            db.putMessage(original)
            c.getSharedPreferences("sync-baseline",Context.MODE_PRIVATE).edit().putInt("count:a",25).putInt("unread:a",7).commit()
            val repo=MailRepository(c);repo.load()
            assertEquals(setOf(original.id),repo.spamMessages(setOf(original.id),false).removed)
            assertEquals(24,repo.snapshot.value.history["a"]?.total)
            assertEquals(6,repo.snapshot.value.history["a"]?.unread)
            val queued=repo.snapshot.value.actions.single()
            assertTrue(repo.snapshot.value.messages.single().pendingDelete)
            repo.cancelAction(queued.id)
            assertEquals(25,repo.snapshot.value.history["a"]?.total)
            assertEquals(7,repo.snapshot.value.history["a"]?.unread)
            assertFalse(repo.snapshot.value.messages.single().pendingDelete)
            assertTrue(repo.snapshot.value.actions.isEmpty())
            assertEquals(original.body,db.message(original.id)?.body)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun offlineDeletedUnreadMailDisappearsImmediatelyAndCancellationRestoresIt()=kotlinx.coroutines.runBlocking {
        val c=context();val db=LocalStore(c)
        try {
            db.putAccount(account())
            val original=message().copy(uid=12,validity=7,localFolder="unread-cache",unread=true)
            db.putMessage(original)
            c.getSharedPreferences("sync-baseline",Context.MODE_PRIVATE).edit().putInt("count:a",25).putInt("unread:a",7).commit()
            val repo=MailRepository(c);repo.load()
            assertEquals(setOf(original.id),repo.deleteMessages(setOf(original.id)).removed)
            assertTrue(MailRules.filter(repo.snapshot.value.messages,null,MailFolder.UNREAD,"").isEmpty())
            assertEquals(24,repo.snapshot.value.history["a"]?.total)
            assertEquals(6,repo.snapshot.value.history["a"]?.unread)
            assertEquals(original.body,db.message(original.id)?.body)
            repo.cancelAction(repo.snapshot.value.actions.single().id)
            assertEquals(original.id,MailRules.filter(repo.snapshot.value.messages,null,MailFolder.UNREAD,"").single().id)
            assertEquals(25,repo.snapshot.value.history["a"]?.total)
            assertEquals(7,repo.snapshot.value.history["a"]?.unread)
            assertEquals(original.body,db.message(original.id)?.body)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }
    @Test fun accountColorsAndResponseMarkersSurviveDatabaseReopenAndOldRecordsRemainReadable() {
        val c=context();var db=LocalStore(c)
        try {
            val account=account().copy(colorSlot=7);db.putAccount(account)
            val draft=message().copy(answered=true,forwarded=true,responseTo="a:7:3",responseKind="reply")
            db.putMessage(draft)
            val legacy=db.encodeMessage(draft).apply{remove("answered");remove("forwarded");remove("responseTo");remove("responseKind")}
            val old=db.decodeMessage(legacy)
            assertFalse(old.answered);assertFalse(old.forwarded);assertNull(old.responseTo)
            db.close();db=LocalStore(c)
            assertEquals(7,db.accounts().single().colorSlot)
            assertTrue(db.message(draft.id)!!.answered);assertTrue(db.message(draft.id)!!.forwarded)
            assertEquals("a:7:3",db.message(draft.id)!!.responseTo)
            assertEquals("reply",db.message(draft.id)!!.responseKind)
        }finally{db.close();c.deleteDatabase("mail-v1.db")}
    }

}
