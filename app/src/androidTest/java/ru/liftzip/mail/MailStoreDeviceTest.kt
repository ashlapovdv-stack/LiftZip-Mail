package ru.liftzip.mail

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    @Test fun startupPublishesIndexedInboxBeforeTheCompleteSnapshot(): Unit = runBlocking {
        val c=context()
        try {
            val count=5_000
            LocalStore(c).use{db->
                db.putAccount(account().copy(enabled=true))
                db.putAccount(account().copy(id="off",email="off@example.com"))
                db.putMessages((1..count).map{i->message().copy(id="in-$i",uid=i.toLong(),validity=1,date=i.toLong(),localFolder="inbox",unread=false)}+
                    message("off").copy(id="disabled",uid=1,validity=1,date=count+1L,localFolder="inbox"))
                db.putAction(MailAction("trash","a","in-5000",ActionKind.TRASH,"{}"))
                db.putAction(MailAction("star","a","in-4999",ActionKind.FLAGS,"{}",starred=true))
            }
            val repo=MailRepository(c)
            val start=System.nanoTime()
            val first=async(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                val page=withTimeout(10_000){repo.startupInbox.filterNotNull().first()}
                assertTrue("The complete snapshot was already published",repo.snapshot.value.messages.isEmpty())
                (System.nanoTime()-start)/1_000_000 to page
            }
            repo.load()
            val fullMs=(System.nanoTime()-start)/1_000_000
            val (firstMs,preview)=first.await()
            assertEquals(LOCAL_INBOX_PAGE_SIZE,preview.messages.size)
            assertEquals((4999 downTo 4960).map{"in-$it"},preview.messages.map{it.id})
            assertTrue(preview.messages.first().starred)
            assertEquals(preview.messages,repo.localInboxPage(null,null,LOCAL_INBOX_PAGE_SIZE).items)
            assertEquals(count+1,repo.snapshot.value.messages.size)
            assertNull(repo.startupInbox.value)
            android.util.Log.i("MailStartup","rows=${count+1} first_page_ms=$firstMs full_initialization_ms=$fullMs")
        }finally{c.deleteDatabase("mail-v1.db")}
    }

    @Test fun storedMimeHistoryIsReanalyzedAfterReopeningWithoutChangingItsSource() {
        val mime=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("mailru-linked-history.eml").use{input->
                MimeReader().read(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),input))
            }
        val c=context()
        val original=message().copy(body=mime.text,htmlBody=mime.html,localFolder="inbox",uid=42,validity=7,
            contentCached=true,contentVersion=MailContent.FORMAT_VERSION)
        LocalStore(c).use{db->db.putAccount(account());db.putMessage(original)}
        try {
            LocalStore(c).use{db->
                val stored=requireNotNull(db.message(original.id))
                assertEquals(original.body,stored.body);assertEquals(original.htmlBody,stored.htmlBody)
                val analysis=HtmlMail.analyzeHistory(stored.htmlBody,ConversationText.parse(stored.body))
                assertTrue(analysis.reason.toString(),analysis.separateHistory)
                assertEquals("Текущий ответ",org.jsoup.Jsoup.parse(analysis.currentHtml).text().trim())
                assertEquals(original.htmlBody,db.message(original.id)?.htmlBody)
            }
        }finally{c.deleteDatabase("mail-v1.db")}
    }
    @Test fun structuredDraftSurvivesPreviewFlagUpdatesQueueSnapshotsAndReopen() {
        val c=context()
        val source=message().copy(id="source",body="Полная история",htmlBody="<table><tr><td>История</td></tr></table>",headers=MessageHeaders("<parent@example.com>","reply@example.com",references=listOf("<first@example.com>")))
        val editor=ComposerSnapshot("Новый текст",ComposeContent.quote(source,"reply"),true,"copy@example.com","private@example.com",bodyHtml="<b>Новый текст</b><table><tbody><tr><td>HTML</td></tr></tbody></table>")
        val draft=message().copy(body="Новый текст и история",composer=editor,cc=editor.cc,bcc=editor.bcc,headers=MessageHeaders("<draft@example.com>"),
            outgoing=listOf(OutgoingFile("content://owned/photo","photo.png","image/png",12,"photo")))
        try {
            LocalStore(c).use{db->
                db.putAccount(account());db.putMessage(source);db.putMessage(draft)
                val payload=db.encodeMessage(draft).toString()
                db.putAction(MailAction("send","a",draft.id,ActionKind.SEND,payload,1))
                db.putMessage(db.messages().first{it.id==draft.id}.copy(starred=true))
                assertEquals(editor,db.message(draft.id)!!.composer)
                db.putMessage(draft.copy(composer=editor.copy(text="Другая редакция",bodyHtml="<i>Другая редакция</i>")))
                assertEquals(editor,db.decodeMessage(org.json.JSONObject(db.action("send")!!.payload)).composer)
                db.removeMessages(setOf(source.id))
            }
            LocalStore(c).use{db->
                val restored=db.message(draft.id)!!
                assertEquals(editor.quote,restored.composer!!.quote)
                assertEquals("Другая редакция",restored.composer!!.text)
                assertEquals("<i>Другая редакция</i>",restored.composer!!.bodyHtml)
                assertEquals("private@example.com",restored.bcc)
                assertEquals("photo",restored.outgoing.single().contentId)
                assertEquals("<draft@example.com>",restored.headers!!.messageId)
                assertNull(db.message(source.id))
            }
        }finally{c.deleteDatabase("mail-v1.db")}
    }
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
    @Test fun folderSyncCommitsMessagesFlagsAndFolderStateTogether() {
        val c = context()
        val db = LocalStore(c)
        val sent = ServerFolder("a", "Sent", "Sent", FolderRole.SENT, total = 1, validity = 7)
        val updated = sent.copy(total = 2, checkedAt = 100)
        val existing = message().copy(localFolder = "remote-sent", remoteFolder = sent.path, uid = 10, validity = sent.validity)
        val incoming = existing.copy(id = "new-sent", uid = 11)
        val flag = MailAction("sent-flag", "a", incoming.id, ActionKind.FLAGS, "{}")
        try {
            db.putAccount(account())
            db.putFolders(listOf(sent))
            db.putMessage(existing)
            db.writableDatabase.execSQL("""
                CREATE TRIGGER fail_folder_write BEFORE INSERT ON server_folders
                BEGIN SELECT RAISE(ABORT, 'test folder write failure'); END
            """.trimIndent())

            assertThrows(SQLiteException::class.java) {
                db.applyFolderSync(listOf(existing), listOf(incoming), updated, listOf(flag))
            }
            assertEquals(listOf(sent), db.folders())
            assertEquals(listOf(existing.id), db.messages().map { it.id })
            assertTrue(db.actions().isEmpty())

            db.writableDatabase.execSQL("DROP TRIGGER fail_folder_write")
            db.applyFolderSync(listOf(existing), listOf(existing, incoming), updated, listOf(flag))
            db.close()
            LocalStore(c).use { reopened ->
                assertEquals(listOf(updated), reopened.folders())
                assertEquals(setOf(existing.id, incoming.id), reopened.messages().map { it.id }.toSet())
                assertEquals(existing.body, reopened.message(existing.id)?.body)
                assertEquals(flag.id, reopened.actions().single().id)
            }
        } finally {
            db.close()
            c.deleteDatabase("mail-v1.db")
        }
    }

    @Test fun folderDiscoveryRollsBackWhenRemovingAnObsoleteMessageFails() {
        val c=context()
        val removed=ServerFolder("a","Old","Old",FolderRole.OTHER)
        val discovered=ServerFolder("a","New","New",FolderRole.OTHER)
        val obsolete=message().copy(localFolder="remote-other",remoteFolder=removed.path,uid=10)
        try {
            LocalStore(c).use{ db->
                db.putAccount(account())
                db.putFolders(listOf(removed))
                db.putMessage(obsolete)
                db.writableDatabase.execSQL("""
                    CREATE TRIGGER fail_message_delete BEFORE DELETE ON messages
                    BEGIN SELECT RAISE(ABORT, 'test message delete failure'); END
                """.trimIndent())
                assertThrows(SQLiteException::class.java) {
                    db.replaceFolders("a",listOf(discovered),setOf(obsolete.id))
                }
                assertEquals(listOf(removed),db.folders())
                assertEquals(obsolete.body,db.message(obsolete.id)?.body)

                db.writableDatabase.execSQL("DROP TRIGGER fail_message_delete")
                db.replaceFolders("a",listOf(discovered),setOf(obsolete.id))
                assertEquals(listOf(discovered),db.folders())
                assertNull(db.message(obsolete.id))
            }
        }finally{c.deleteDatabase("mail-v1.db")}
    }

    @Test fun localSearchFindsRecipientsAndFullCachedBody() {
        val c=context()
        try {
            val cached=message().copy(body="prefix ".repeat(100)+"sku-400",cc="copy@example.com",bcc="hidden@example.com")
            LocalStore(c).use{ db->
                db.putAccount(account())
                db.putMessage(cached)
                db.putMessage(cached.copy(id="other",body="Other letter",recipients="unrelated@example.com",cc="",bcc=""))
                assertEquals(setOf(cached.id),db.search("r@example.com sku-400"){})
                assertEquals(setOf(cached.id),db.search("COPY@EXAMPLE.COM hidden@example.com"){})
                assertEquals(emptySet<String>(),db.search("copy@example.com missing"){})
            }
        }finally{c.deleteDatabase("mail-v1.db")}
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
            assertEquals(3,helper.readableDatabase.version)
            assertEquals("encrypted-old-secret",helper.accounts().single().secret)
            assertEquals(m.body,helper.message(m.id)?.body)
            assertTrue(helper.messages().single().body.length<=MailContent.PREVIEW_LENGTH)
            assertEquals(m.body,helper.message(m.id)?.body)
            assertTrue(helper.actions().isEmpty());assertTrue(helper.folders().isEmpty())
        }finally{if(legacy.isOpen)legacy.close();helper.close();c.deleteDatabase("mail-v1.db")}
    }

    @Test fun versionTwoUpgradeKeepsMessagesAndPagesTiedDatesAcrossAccounts() {
        val c=context()
        val helper=LocalStore(c)
        val file="mail-v1.db"
        try {
            c.openOrCreateDatabase(file,0,null).use{db->
                db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,data TEXT NOT NULL)")
                db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,folder TEXT NOT NULL,data TEXT NOT NULL)")
                helper.onUpgrade(db,1,2)
                db.execSQL("INSERT INTO accounts VALUES('a','{}'),('b','{}')")
                val ids=(1..1050).map{"m"+it.toString().padStart(4,'0')}
                db.beginTransaction()
                try {
                    for((i,id) in ids.withIndex()) {
                        val item=message(if(i%2==0)"a" else "b").copy(id=id,date=5000,localFolder="inbox",body="Complete body",htmlBody="<b>Letter</b>")
                        db.execSQL("INSERT INTO messages(id,account_id,folder,data,preview) VALUES(?,?,?,?,?)",arrayOf(item.id,item.accountId,item.localFolder,helper.encodeMessage(item).toString(),
                            if(i%3==0)null else helper.encodeMessage(MailContent.preview(item)).toString()))
                    }
                    val draft=message().copy(id="edit",sourceDraftId=ids.last())
                    db.execSQL("INSERT INTO messages(id,account_id,folder,data) VALUES(?,?,?,?)",arrayOf(draft.id,draft.accountId,draft.localFolder,helper.encodeMessage(draft).toString()))
                    db.execSQL("INSERT INTO mail_actions VALUES('queued','a',1,'{}')")
                    db.setTransactionSuccessful()
                }finally{db.endTransaction()}
                db.version=2
            }
            var after: LocalInboxCursor?=null
            val ids=mutableListOf<String>()
            repeat(27) {
                val page=helper.inboxRows(setOf("a","b"),after,40)
                assertTrue(page.isNotEmpty())
                assertTrue(page.all{it.previewOnly})
                ids+=page.map{it.id}
                after=page.last().inboxCursor()
            }
            assertEquals((1049 downTo 1).map{"m"+it.toString().padStart(4,'0')},ids)
            assertTrue(helper.inboxRows(setOf("a","b"),after,40).isEmpty())
            assertEquals(listOf("m1049","m1047"),helper.inboxRows(setOf("a"),null,2).map{it.id})
            assertEquals("<b>Letter</b>",helper.message("m1050")?.htmlBody)
            assertEquals("m1050",helper.message("edit")?.sourceDraftId)
            helper.readableDatabase.rawQuery("SELECT revision FROM mail_actions WHERE id='queued'",null).use{
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
            helper.putMessage(message().copy(id="new",date=9000,localFolder="inbox"))
            assertEquals("new",helper.inboxRows(setOf("a"),null,1).single().id)
            helper.close()
            LocalStore(c).use{reopened->
                assertEquals(listOf("new","m1049"),reopened.inboxRows(setOf("a"),null,2).map{it.id})
                assertEquals("Complete body",reopened.message("m1050")?.body)
            }
        }finally{helper.close();c.deleteDatabase(file)}
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
