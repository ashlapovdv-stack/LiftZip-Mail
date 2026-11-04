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

}
