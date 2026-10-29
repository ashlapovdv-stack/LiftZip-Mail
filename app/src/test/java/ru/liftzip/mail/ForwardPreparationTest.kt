package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ForwardPreparationTest {
    private fun message()=MailItem("m","origin",10,20,sender="Sender",senderEmail="a@example.com",recipients="b@example.com",subject="Test",body="Body",date=0,
        attachments=listOf(Attachment("1","same.pdf","application/pdf",-1),Attachment("2","same.pdf","application/pdf",100)))
    private fun ready(f: ForwardFile)=f.copy(status=ForwardStatus.READY,prepared=OutgoingFile("content://files/${f.key}",f.name,f.mime,100),bytes=100)
    @Test fun initialPlanContainsAllFilesAndDoesNotPrepareAnyOfThem() {
        val p=ForwardRules.plan(message())!!
        assertEquals(2,p.files.size)
        assertNotEquals(p.files[0].key,p.files[1].key)
        assertEquals(listOf("1","2"),p.files.map{it.path})
        assertTrue(p.active);assertFalse(p.ready)
        assertTrue(p.files.all{it.prepared==null && it.bytes==0L})
    }
    @Test fun messagesWithoutFilesNeedNoPreparation() { assertNull(ForwardRules.plan(message().copy(attachments=emptyList()))) }
    @Test fun locallySentAttachmentsAreAlsoForwarded() {
        val p=ForwardRules.plan(message().copy(attachments=emptyList(),outgoing=listOf(OutgoingFile("content://local/file","data.pdf","application/pdf",20))))!!
        assertEquals("content://local/file",p.files.single().sourceUri)
        assertNull(p.files.single().path)
    }
    @Test fun sendIsBlockedForWaitingDownloadingAndFailedFiles() {
        for(status in listOf(ForwardStatus.WAITING,ForwardStatus.DOWNLOADING,ForwardStatus.ERROR)) {
            val p=ForwardRules.plan(message())!!
            val d=ComposeDraft(accountId="sender",to="b@example.com",subject="Test",body="Text",forwarding=p.copy(files=p.files.map{it.copy(status=status)}))
            assertNotNull(MailRules.validateDraft(d))
            assertThrows(IllegalArgumentException::class.java){ForwardRules.sending(d)}
        }
    }
    @Test fun finalSendIncludesForwardedAndManuallyAddedFilesExactlyOnce() {
        val p=ForwardRules.plan(message())!!.let{it.copy(files=it.files.map(::ready))}
        val own=OutgoingFile("content://manual","extra.txt","text/plain",10)
        val d=ComposeDraft(accountId="sender",to="b@example.com",subject="Test",body="Text",attachments=listOf(own),forwarding=p)
        assertNull(MailRules.validateDraft(d))
        val sent=ForwardRules.sending(d)
        assertEquals(3,sent.attachments.size)
        assertNull(sent.forwarding)
        assertEquals(3,ForwardRules.sending(sent).attachments.size)
    }
    @Test fun savingTextAndChangingSenderCannotRollBackWorkerProgress() {
        val p=ForwardRules.plan(message())!!
        val editor=ComposeDraft(accountId="another",to="new@example.com",subject="Edited",body="New text",forwarding=p)
        val worker=p.copy(files=p.files.map(::ready))
        val saved=ForwardRules.mergeEditor(editor,worker)
        assertEquals("another",saved.accountId)
        assertEquals("New text",saved.body)
        assertEquals("new@example.com",saved.to)
        assertTrue(saved.forwarding!!.ready)
    }
    @Test fun savingEditorDoesNotResurrectRemovedAttachments() {
        val p=ForwardRules.plan(message())!!
        val saved=ForwardRules.mergeEditor(ComposeDraft(accountId="sender",forwarding=p),p.copy(files=emptyList()))
        assertTrue(saved.forwarding!!.ready)
        assertTrue(saved.filesToSend().isEmpty())
    }
    @Test fun draftPreparationCanSurviveSavedStateSerialization() {
        val p=ForwardRules.plan(message())!!.let{it.copy(files=listOf(ready(it.files[0]),it.files[1].copy(status=ForwardStatus.DOWNLOADING,bytes=45)))}
        val draft=ComposeDraft(accountId="sender",body="Editing",forwarding=p)
        val buffer=ByteArrayOutputStream()
        ObjectOutputStream(buffer).use{it.writeObject(draft)}
        val restored=ObjectInputStream(ByteArrayInputStream(buffer.toByteArray())).use{it.readObject()} as ComposeDraft
        assertEquals(draft,restored)
        assertTrue(restored.forwarding!!.active)
        assertEquals(1,restored.filesToSend().size)
    }
    @Test fun declaredSizeCannotBypassCombinedLimitWithUserFiles() {
        val p=ForwardRules.plan(message())!!.let{it.copy(files=it.files.map{f->ready(f).copy(prepared=OutgoingFile("content://${f.key}",f.name,f.mime,14L*1024*1024))})}
        assertNotNull(MailRules.validateDraft(ComposeDraft(accountId="sender",to="b@example.com",subject="Test",body="Text",forwarding=p)))
    }
    @Test fun parallelUnknownLengthTransfersShareOneSizeBudget() {
        val budget=ForwardBudget()
        val start=CountDownLatch(1)
        val accepted=AtomicInteger()
        val rejected=AtomicInteger()
        val threads=(1..2).map{key->Thread{
            start.await()
            try{budget.progress(key.toString(),14L*1024*1024);accepted.incrementAndGet()}catch(_: IllegalArgumentException){rejected.incrementAndGet()}
        }.apply{start()}}
        start.countDown();threads.forEach{it.join(5000);assertFalse(it.isAlive)}
        assertEquals(1,accepted.get());assertEquals(1,rejected.get())
    }
    @Test fun retryBudgetReplacesPartialLengthRatherThanAddingItAgain() {
        val budget=ForwardBudget()
        budget.progress("a",10L*1024*1024)
        budget.progress("a",12L*1024*1024)
        budget.progress("b",12L*1024*1024)
        budget.remove("a")
        budget.progress("c",12L*1024*1024)
    }
    @Test fun individualLimitIsCheckedEvenWithUnknownSize() {
        assertThrows(IllegalArgumentException::class.java){ForwardBudget().progress("a",21L*1024*1024)}
    }
}
