package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ServerFeaturesTest {
    private fun mail(id: String="m",folder: String="inbox",unread: Boolean=true)=MailItem(id,"a",3,10,sender="S",senderEmail="s@example.com",recipients="r@example.com",subject="Subject",body="Text",date=1,unread=unread,localFolder=folder)
    private fun account()=MailAccount(id="a",name="Google",email="a@gmail.com",provider=Provider.GMAIL,imapHost="imap.gmail.com",smtpHost="smtp.gmail.com",authKind=AuthKind.GOOGLE)
    private fun action(kind: ActionKind=ActionKind.FLAGS,status: String="pending")=MailAction("x","a","m",kind,"{}",created=1,status=status)
    @Test fun googleTokensCannotBeSentToACustomHost() {
        assertNull(AuthRules.validateGoogle(account()))
        assertNotNull(AuthRules.validateGoogle(account().copy(imapHost="attacker.example")))
        assertNotNull(AuthRules.validateGoogle(account().copy(smtpHost="smtp.example")))
        assertNotNull(AuthRules.validateGoogle(account().copy(username="other@gmail.com")))
    }
    @Test fun googleRequiresTheCorrectTlsPorts() {
        assertNull(AuthRules.validateGoogle(account().copy(smtpPort=587,smtpStartTls=true)))
        assertNotNull(AuthRules.validateGoogle(account().copy(imapPort=143,imapStartTls=true)))
        assertNotNull(AuthRules.validateGoogle(account().copy(smtpPort=587,smtpStartTls=false)))
    }
    @Test fun oauthDoesNotRequireAStoredPassword() {
        assertNull(MailRules.validateAccount(account(),""))
        assertNotNull(MailRules.validateAccount(account().copy(authKind=AuthKind.PASSWORD),""))
    }
    @Test fun inboxIdsRemainCompatibleButOtherFoldersCannotCollide() {
        assertEquals("a:10:3",FolderRules.id("a","INBOX",10,3))
        assertNotEquals(FolderRules.id("a","INBOX",10,3),FolderRules.id("a","Sent",10,3))
        assertNotEquals(FolderRules.id("a","Drafts",10,3),FolderRules.id("a","Sent",10,3))
        assertNotEquals(FolderRules.id("a","folder/one",10,3),FolderRules.id("a","folder:one",10,3))
    }
    @Test fun specialUseRolesOverrideLocalizedFolderNames() {
        assertEquals(FolderRole.SENT,FolderRules.role("Моя папка",listOf("\\Sent")))
        assertEquals(FolderRole.DRAFTS,FolderRules.role("Черновики",emptyList()))
        assertEquals(FolderRole.TRASH,FolderRules.role("[Gmail]/Корзина",emptyList()))
        assertEquals(FolderRole.ARCHIVE,FolderRules.role("[Gmail]/All Mail",listOf("\\All")))
    }
    @Test fun trashAndDraftsDoNotPolluteUnreadView() {
        assertTrue(FolderRules.unread(mail()))
        listOf("remote-trash","remote-junk","remote-drafts","remote-sent","outbox").forEach{assertFalse(FolderRules.unread(mail(folder=it)))}
        assertFalse(FolderRules.unread(mail().copy(pendingDelete=true)))
    }
    @Test fun editingAServerDraftShowsTheLocalEditOnly() {
        val source=mail("remote",folder="remote-drafts")
        val local=mail("edit",folder="drafts").copy(uid=0,sourceDraftId=source.id)
        assertEquals(listOf(local),FolderRules.display(listOf(source,local)))
    }
    @Test fun duplicateLabelsCanShareAMessageWithoutDuplicateCombinedRows() {
        val inbox=mail().copy(internetMessageId="<one@example.com>")
        val archive=mail("archive",folder="remote-archive").copy(internetMessageId=inbox.internetMessageId)
        assertEquals(listOf(inbox),FolderRules.deduplicate(listOf(archive,inbox)))
    }
    @Test fun anotherMailboxOrMissingMessageIdIsNotMergedAway() {
        assertEquals(2,FolderRules.deduplicate(listOf(mail("one"),mail("two"))).size)
        val one=mail().copy(internetMessageId="<one@example.com>")
        assertEquals(2,FolderRules.deduplicate(listOf(one,one.copy(id="other",accountId="b"))).size)
    }
    @Test fun queuedFlagsOverrideAStaleServerSnapshot() {
        val pending=action().copy(unread=false,starred=true)
        val actual=ActionRules.overlay(listOf(mail()),listOf(pending)).single()
        assertFalse(actual.unread);assertTrue(actual.starred)
    }
    @Test fun pendingDeleteIsHiddenButARejectedDeleteReturnsToTheList() {
        val m=ActionRules.overlay(listOf(mail()),listOf(action(ActionKind.TRASH))).single()
        assertTrue(m.pendingDelete);assertTrue(FolderRules.display(listOf(m)).isEmpty())
        assertFalse(ActionRules.overlay(listOf(m),listOf(action(ActionKind.TRASH,"failed"))).single().pendingDelete)
    }
    @Test fun backgroundRecoveryNeverBlindlyResendsAnInterruptedSmtpAttempt() {
        val original=action(ActionKind.SEND,"sending").copy(lease="old")
        val recovered=ActionRules.recovered(original,"new")
        assertEquals("uncertain",recovered.status)
        assertFalse(ActionRules.ready(recovered,listOf(account()),1000))
    }
    @Test fun idempotentActionsCanResumeAfterProcessDeath() {
        val recovered=ActionRules.recovered(action(status="running").copy(lease="old"),"new")
        assertEquals("pending",recovered.status)
        assertTrue(ActionRules.ready(recovered,listOf(account()),1000))
    }
    @Test fun aSecondUiLoadCannotResetTheCurrentlyRunningWorker() {
        val current=action(status="running").copy(lease="same")
        assertEquals(current,ActionRules.recovered(current,"same"))
    }
    @Test fun disabledAccountsAndBackoffAreRespected() {
        assertFalse(ActionRules.ready(action(),listOf(account().copy(enabled=false)),1000))
        assertFalse(ActionRules.ready(action().copy(nextAttempt=2000),listOf(account()),1000))
        assertTrue(ActionRules.retryAt(1000,100)<=1000+15*60_000)
    }
    @Test fun messageIdentityIsStableAndRejectsHeaderInjection() {
        assertEquals("<id@local.liftzip.mail>",MessageIdentity.forDraft("id"))
        assertEquals("<old@example.com>",MessageIdentity.forDraft("id","<old@example.com>"))
        assertFalse(MessageIdentity.forDraft("id","<evil@example.com>\r\nBcc: victim@example.com").contains('\n'))
        assertEquals(MessageIdentity.forDraft("remote:folder/uid"),MessageIdentity.forDraft("remote:folder/uid"))
    }
}
