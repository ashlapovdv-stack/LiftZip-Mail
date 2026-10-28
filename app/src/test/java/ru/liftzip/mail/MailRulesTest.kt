package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class MailRulesTest {
    private fun mail(id: String,account: String="a",folder: String="inbox",time: Long=1,unread: Boolean=true,validity: Long=10)=MailItem(
        id,account,uid=1,validity=validity,sender="Анна",senderEmail="anna@example.com",recipients="user@example.com",subject="Энкодер TS5690N9038",body="Запрос поставки",date=time,unread=unread,localFolder=folder)
    @Test fun accountFilterDoesNotLeakOtherAccounts() {
        val result=MailRules.filter(listOf(mail("1"),mail("2","b")),"a",MailFolder.INBOX,"")
        assertEquals(listOf("1"),result.map{it.id})
    }
    @Test fun unifiedInboxIncludesAccountsAndSortsNewestFirst() {
        val result=MailRules.filter(listOf(mail("1",time=1),mail("2","b",time=3),mail("3",folder="drafts",time=5)),null,MailFolder.INBOX,"")
        assertEquals(listOf("2","1"),result.map{it.id})
    }
    @Test fun searchMatchesAllTermsInAnyOrder() {
        assertEquals(1,MailRules.filter(listOf(mail("1")),null,MailFolder.INBOX,"ts5690 АННА").size)
        assertTrue(MailRules.filter(listOf(mail("1")),null,MailFolder.INBOX,"ts5690 тормоз").isEmpty())
    }
    @Test fun starredSearchDoesNotShowDrafts() {
        val list=listOf(mail("1").copy(starred=true),mail("2",folder="drafts").copy(starred=true))
        assertEquals(listOf("1"),MailRules.filter(list,null,MailFolder.STARRED,"").map{it.id})
    }
    @Test fun firstSynchronizationDoesNotNotifyOldMail() {
        assertEquals(0,MailRules.newUnread(emptyList(),listOf(mail("1")),false))
    }
    @Test fun arrivalAfterEmptyInboxNotifies() {
        assertEquals(1,MailRules.newUnread(emptyList(),listOf(mail("1")),true))
    }
    @Test fun knownOrReadMessagesDoNotNotifyAgain() {
        assertEquals(1,MailRules.newUnread(listOf(mail("1")),listOf(mail("1"),mail("2"),mail("3",unread=false)),true))
    }
    @Test fun changedUidValidityDoesNotNotifyEntireMailbox() {
        assertEquals(0,MailRules.newUnread(listOf(mail("1",validity=1)),listOf(mail("2",validity=2)),true))
    }
    @Test fun badRecipientIsRejected() {
        assertNotNull(MailRules.validateDraft(ComposeDraft(accountId="a",to="good@example.com, broken",subject="Тема",body="Текст")))
    }
    @Test fun validDraftAcceptsMultipleRecipients() {
        assertNull(MailRules.validateDraft(ComposeDraft(accountId="a",to="a@example.com; b@example.com",subject="Тема",body="Текст")))
    }
    @Test fun subjectCannotInjectAdditionalHeaders() {
        assertNotNull(MailRules.validateDraft(ComposeDraft(accountId="a",to="a@example.com",subject="Subject\r\nBcc: other@example.com",body="Text")))
    }
    @Test fun invalidPortsAndHostUrlsRejected() {
        val account=MailAccount(name="Проверка",email="test@example.com",provider=Provider.CUSTOM,imapHost="https://imap.example.com",smtpHost="smtp.example.com")
        assertNotNull(MailRules.validateAccount(account,"password"))
        assertNotNull(MailRules.validateAccount(account.copy(imapHost="imap.example.com",imapPort=70000),"password"))
        assertNull(MailRules.validateAccount(account.copy(imapHost="imap.example.com"),"password"))
    }
    @Test fun totalAttachmentLimitIsEnforced() {
        val files=listOf(OutgoingFile("uri1","a.bin","application/octet-stream",14L*1024*1024),OutgoingFile("uri2","b.bin","application/octet-stream",14L*1024*1024))
        assertNotNull(MailRules.validateDraft(ComposeDraft(accountId="a",to="a@example.com",subject="A",body="B",attachments=files)))
    }
}
