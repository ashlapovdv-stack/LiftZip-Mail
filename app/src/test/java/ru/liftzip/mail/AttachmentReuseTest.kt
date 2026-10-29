package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class AttachmentReuseTest {
    @get:Rule val temp=TemporaryFolder()
    private fun message(account: String="a")=MailItem("$account:1:10",account,10,1,sender="",senderEmail="",recipients="",subject="",body="",date=0)
    private val attachment=Attachment("1","file.txt","text/plain",-1)
    @Test fun repeatedForwardUsesFirstDurableFileWithoutAnotherNetworkRead() {
        val cache=AttachmentCache(temp.newFolder()){it.toURI().toString()}
        val storage=ForwardAttachments(temp.newFolder()){it.toURI().toString()}
        val first=storage.prepareFile("first","1","file.txt","text/plain",-1){it.write("Complete".toByteArray())}
        cache.remember(message(),attachment,File(URI(first.uri)))
        val reused=cache.existing(message(),attachment)
        assertNotNull(reused)
        val second=storage.prepareFile("second","1","file.txt","text/plain",-1,cached=reused){fail("Must reuse file")}
        storage.remove("first");cache.removeAccount("a")
        assertEquals("Complete",File(URI(second.uri)).readText())
    }
    @Test fun previewAfterForwardUsesTheSameSavedBytes() {
        val cache=AttachmentCache(temp.newFolder()){it.toURI().toString()}
        val source=temp.newFile().apply{writeText("Already downloaded")}
        cache.remember(message(),attachment,source)
        val preview=cache.prepare(message(),attachment){fail("No repeated download")}
        assertEquals("Already downloaded",preview.readText())
    }
    @Test fun identicalFileNamesInDifferentMailboxesNeverMix() {
        val cache=AttachmentCache(temp.newFolder()){it.toURI().toString()}
        val a=temp.newFile().apply{writeText("A")};val b=temp.newFile().apply{writeText("B")}
        cache.remember(message("a"),attachment,a);cache.remember(message("b"),attachment,b)
        assertEquals("A",cache.existing(message("a"),attachment)!!.readText())
        assertEquals("B",cache.existing(message("b"),attachment)!!.readText())
        cache.removeAccount("a")
        assertNull(cache.existing(message("a"),attachment))
        assertEquals("B",cache.existing(message("b"),attachment)!!.readText())
    }
}
