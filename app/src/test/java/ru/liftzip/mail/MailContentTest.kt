package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class MailContentTest {
    private fun mail(body: String)=MailItem("message","account",sender="Sender",senderEmail="sender@example.com",recipients="sales@example.com",subject="Invoice",body=body,date=1)
    @Test fun listPreviewIsBoundedWhileTheOriginalMessageIsUnchanged() {
        val full=mail("текст ".repeat(10000))
        val preview=MailContent.preview(full)
        assertEquals(240,preview.body.length)
        assertEquals(full.id,preview.id)
        assertTrue(preview.previewOnly)
        assertEquals(60000,full.body.length)
        assertFalse(full.previewOnly)
    }

    @Test fun updatingReadStatusFromAPreviewPreservesTheWholeStoredBody() {
        val body="Длинный текст письма\n".repeat(500)
        val preview=mail(body.take(MailContent.PREVIEW_LENGTH)).copy(previewOnly=true,unread=false,starred=true)
        val saved=MailContent.forStorage(preview,body)
        assertEquals(body,saved.body)
        assertFalse(saved.unread)
        assertTrue(saved.starred)
        assertFalse(saved.previewOnly)
    }
    @Test fun newOrEditedFullMessagesUseTheirNewBody() {
        assertEquals("edited",MailContent.forStorage(mail("edited"),"old").body)
        assertEquals("new",MailContent.forStorage(mail("new"),null).body)
    }
    @Test(expected=IllegalArgumentException::class) fun aPreviewWithoutStoredContentCannotReplaceAFullEmail() {
        MailContent.forStorage(mail("preview").copy(previewOnly=true),null)
    }
    @Test fun anEmptyButCompleteStoredBodyIsValid() {
        assertEquals("",MailContent.forStorage(mail("").copy(previewOnly=true),"").body)
    }
    @Test fun fullTextSearchCanFindTextBeyondTheListPreview() {
        val full=mail("x".repeat(5000)+" Номер заказа АБВ-123")
        assertTrue(MailContent.matches(full,listOf("invoice","абв-123")))
        assertFalse(MailContent.matches(full.copy(body=full.body.take(MailContent.PREVIEW_LENGTH)),listOf("абв-123")))
    }
    @Test fun allSearchWordsMustBePresentAcrossSenderSubjectAndBody() {
        assertTrue(MailContent.matches(mail("Total amount"),listOf("sender","invoice","amount")))
        assertFalse(MailContent.matches(mail("Total amount"),listOf("sender","missing")))
    }
}
