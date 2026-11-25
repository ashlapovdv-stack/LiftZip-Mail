package ru.liftzip.mail

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    @SdkSuppress(minSdkVersion = 29)
    @Test fun attachmentGoesToPublicDownloadsWithoutReplacingAnExistingFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val name = "mail-download-${UUID.randomUUID()}.txt"
        val source = File(context.cacheDir, name)
        val saved = mutableListOf<Uri>()
        val attachment = Attachment("1", name, "application/octet-stream", 0)
        try {
            source.writeText("first attachment")
            saved += saveInDownloads(resolver, attachment, source)
            source.writeText("second attachment")
            saved += saveInDownloads(resolver, attachment, source)

            assertNotEquals(saved[0], saved[1])
            val names = saved.map { uri ->
                val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING)
                resolver.query(uri, columns, null, null, null)!!.use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(Environment.DIRECTORY_DOWNLOADS, row.getString(1).trimEnd('/'))
                    assertEquals("text/plain", row.getString(2))
                    assertEquals(0, row.getInt(3))
                    row.getString(0)
                }
            }
            assertEquals(name, names[0])
            assertNotEquals(names[0], names[1])
            assertEquals(listOf("first attachment", "second attachment"), saved.map { uri ->
                resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            })
        } finally {
            saved.forEach { resolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun queuedAttachmentIsAnImmutableCopyOfTheSelectedFile() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val source=File(c.cacheDir,"queue-test-"+UUID.randomUUID()+".txt")
        var target: android.net.Uri?=null
        try {
            source.writeText("original content")
            val draft=ComposeDraft(accountId="test",attachments=listOf(OutgoingFile(android.net.Uri.fromFile(source).toString(),"test.txt","text/plain",source.length(),"inline-id")))
            val frozen=QueueFiles(c).freeze(draft);target=android.net.Uri.parse(frozen.attachments.single().uri)
            source.writeText("changed")
            assertEquals("original content",c.contentResolver.openInputStream(target)!!.bufferedReader().use{it.readText()})
            assertEquals(16L,frozen.attachments.single().size)
            assertEquals("inline-id",frozen.attachments.single().contentId)
        }finally{source.delete();target?.let{c.contentResolver.delete(it,null,null)}}
    }
    @Test fun aLargeEditorSnapshotIsRestoredThroughATinySavedStateToken() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val root=File(c.cacheDir,"editor-state-test-"+UUID.randomUUID())
        val source=MailItem("source","a",sender="Sender",senderEmail="sender@example.com",recipients="a@example.com",subject="Large",body="Text ".repeat(100_000),htmlBody="<p>"+"HTML ".repeat(160_000)+"</p>",date=1)
        val draft=ComposerHtml.replace(ComposeDraft(accountId="a",to="client@example.com",subject="Ответ",body="Не потерять новый текст",quote=ComposeContent.quote(source,"reply"),quoteIncluded=false,cc="copy@example.com"),"<b>Не потерять новый текст</b><table><tr><td>Ячейка</td></tr></table>")
        try {
            val first=DraftSessionCache(root)
            val token=first.save(draft)!!
            val bundle=android.os.Bundle().apply{putString("editor",token)}
            val parcel=android.os.Parcel.obtain()
            try{bundle.writeToParcel(parcel,0);assertTrue("Only a tiny token belongs in Binder",parcel.dataSize()<1024)}finally{parcel.recycle()}
            val afterProcessRestart=DraftSessionCache(root)
            assertEquals(draft,afterProcessRestart.restore(token))
            assertNull(afterProcessRestart.restore("../../outside"))
            afterProcessRestart.clear()
            assertNull(DraftSessionCache(root).restore(token))
        }finally{root.deleteRecursively()}
    }
    @Test fun previewTrimPreservesRecentViewersAndNeverTouchesOtherDirectories() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val root=File(c.cacheDir,"storage-test-"+UUID.randomUUID()).apply{mkdirs()}
        try {
            val previews=File(root,"attachments").apply{mkdirs()}
            val old=File(previews,"old").apply{writeBytes(ByteArray(100));setLastModified(100)}
            val recent=File(previews,"recent").apply{writeBytes(ByteArray(100));setLastModified(999_999)}
            val partial=File(previews,"pending.part").apply{writeText("writing")}
            val draft=File(root,"outbox.txt").apply{writeText("keep")}
            PreviewStorage(previews).trim(0,now=1_000_000)
            assertFalse(old.exists());assertTrue(recent.exists());assertTrue(partial.exists());assertTrue(draft.exists())
            PreviewStorage(previews).trim(0,clear=true)
            assertFalse(recent.exists());assertTrue(partial.exists());assertTrue(draft.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun queueCleanupRemovesItsOwnSnapshotAndPreservesTheOriginalDocument() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val original=File(c.cacheDir,"cleanup-test-"+UUID.randomUUID()+".txt").apply{writeText("keep original")}
        try {
            val queue=QueueFiles(c)
            val frozen=queue.freeze(ComposeDraft(accountId="test",attachments=listOf(OutgoingFile(android.net.Uri.fromFile(original).toString(),"source.txt","text/plain",original.length()))))
            val copy=frozen.attachments.single().uri
            queue.removeUris(setOf(copy,android.net.Uri.fromFile(original).toString(),"content://${c.packageName}.attachments/queued-attachments/../../cache/${original.name}"))
            assertTrue(original.exists());assertEquals("keep original",original.readText())
            try{c.contentResolver.openInputStream(android.net.Uri.parse(copy))?.close();fail("snapshot must be removed")}catch(_: java.io.FileNotFoundException){}
        }finally{original.delete()}
    }

}
