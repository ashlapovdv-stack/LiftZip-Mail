package ru.liftzip.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    @Test fun queuedAttachmentIsAnImmutableCopyOfTheSelectedFile() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        val source=File(c.cacheDir,"queue-test-"+UUID.randomUUID()+".txt")
        var target: android.net.Uri?=null
        try {
            source.writeText("original content")
            val draft=ComposeDraft(accountId="test",attachments=listOf(OutgoingFile(android.net.Uri.fromFile(source).toString(),"test.txt","text/plain",source.length())))
            val frozen=QueueFiles(c).freeze(draft);target=android.net.Uri.parse(frozen.attachments.single().uri)
            source.writeText("changed")
            assertEquals("original content",c.contentResolver.openInputStream(target)!!.bufferedReader().use{it.readText()})
            assertEquals(16L,frozen.attachments.single().size)
        }finally{source.delete();target?.let{c.contentResolver.delete(it,null,null)}}
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
}
