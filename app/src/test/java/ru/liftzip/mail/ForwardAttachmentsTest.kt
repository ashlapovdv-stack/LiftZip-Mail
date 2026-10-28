package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.net.URI

class ForwardAttachmentsTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun store(root: File)=ForwardAttachments(root){it.toURI().toString()}
    private fun source(name: String,text: String)=ForwardSource(name,"application/octet-stream",-1){text.byteInputStream()}
    private fun zeros(size: Int)=object: InputStream() {
        var remaining=size
        override fun read(): Int=if(remaining<=0)-1 else {remaining--;0}
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
            if(remaining<=0)return -1
            val n=minOf(length,remaining);bytes.fill(0,offset,offset+n);remaining-=n;return n
        }
    }
    @Test fun duplicateAttachmentNamesKeepSeparateCompleteCopies() {
        val root=temporary.newFolder()
        val files=store(root).prepare("draft",listOf(source("invoice.txt","Первый файл"),source("invoice.txt","Второй файл")))
        assertEquals(2,files.size)
        assertNotEquals(files[0].uri,files[1].uri)
        assertEquals("Первый файл",File(URI(files[0].uri)).readText())
        assertEquals("Второй файл",File(URI(files[1].uri)).readText())
        assertEquals("Первый файл".toByteArray().size.toLong(),files[0].size)
    }
    @Test fun copiedFilesSurviveReopeningTheDraftStore() {
        val root=temporary.newFolder()
        val copied=store(root).prepare("saved-draft",listOf(source("file.txt","Данные"))).single()
        store(root)
        assertEquals("Данные",File(URI(copied.uri)).readText())
    }
    @Test fun unknownLengthCannotBypassThePerFileLimitAndPartialFilesAreRemoved() {
        val root=temporary.newFolder()
        val file=ForwardSource("big.bin","application/octet-stream",-1){zeros(21*1024*1024)}
        assertThrows(IllegalArgumentException::class.java){store(root).prepare("too-big",listOf(file))}
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun unknownLengthsCannotBypassTheCombinedLimit() {
        val root=temporary.newFolder()
        val file=ForwardSource("a.bin","application/octet-stream",-1){zeros(14*1024*1024)}
        assertThrows(IllegalArgumentException::class.java){store(root).prepare("too-many",listOf(file,file.copy(name="b.bin")))}
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun removingOneDraftKeepsOtherDraftAttachments() {
        val root=temporary.newFolder()
        val storage=store(root)
        val first=storage.prepare("one",listOf(source("a.txt","Один"))).single()
        val second=storage.prepare("two",listOf(source("b.txt","Два"))).single()
        storage.remove("one")
        assertFalse(File(URI(first.uri)).exists())
        assertTrue(File(URI(second.uri)).exists())
    }
}
