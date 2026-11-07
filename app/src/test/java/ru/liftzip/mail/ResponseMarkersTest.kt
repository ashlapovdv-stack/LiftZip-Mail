package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ResponseMarkersTest {
    private fun original()=MailItem("a:7:3","a",3,7,sender="Client",senderEmail="c@example.com",recipients="a@example.com",subject="Question",body="Body",date=1)
    @Test fun acceptedReplyAndForwardSetDistinctFlagsAndKeepBoth() {
        val reply=ResponseMarkers.accepted(original(),"reply")!!
        assertTrue(reply.answered);assertFalse(reply.forwarded)
        val both=ResponseMarkers.accepted(reply,"forward")!!
        assertTrue(both.answered);assertTrue(both.forwarded)
        assertEquals(ActionKind.MARK_ANSWERED,ResponseMarkers.serverAction(reply,"reply"))
        assertEquals(ActionKind.MARK_FORWARDED,ResponseMarkers.serverAction(both,"forward"))
    }
    @Test fun nothingIsMarkedForMissingOriginOrUnrecognizedOperation() {
        assertNull(ResponseMarkers.accepted(null,"reply"))
        assertNull(ResponseMarkers.accepted(original(),"draft"))
        assertNull(ResponseMarkers.serverAction(original().copy(pendingDelete=true),"reply"))
        assertNull(ResponseMarkers.serverAction(original().copy(uid=0),"forward"))
    }
    @Test fun pendingPollsAndContentLoadsCannotEraseAnAcceptedMark() {
        val original=original();val current=original.copy(answered=true,forwarded=true)
        val page=InboxPage(listOf(original),InboxHistory(1,false),7)
        val merged=SyncRules.checkedPage(listOf(original),listOf(current),page,emptySet()).single()
        assertTrue(merged.answered);assertTrue(merged.forwarded)
        val content=SyncRules.contentBatch(listOf(current),ContentBatch(listOf(original.copy(body="Full body")),emptySet())).single()
        assertTrue(content.answered);assertTrue(content.forwarded);assertEquals("Full body",content.body)
    }
    @Test fun preparedDraftKeepsTheOriginWhenAttachmentsAreFlattened() {
        val d=ComposeDraft(accountId="a",to="b@example.com",subject="Re: Question",body="Answer",responseTo="a:7:3",responseKind="reply")
        val prepared=ForwardRules.sending(d)
        assertEquals(d.responseTo,prepared.responseTo);assertEquals("reply",prepared.responseKind)
    }
}
