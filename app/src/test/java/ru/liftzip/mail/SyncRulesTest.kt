package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class SyncRulesTest {
    private fun account(id: String) = MailAccount(id=id,name=id,email="$id@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com")
    private fun mail(id: String,unread: Boolean=true,account: String="a",validity: Long=1,folder: String="inbox",date: Long=1) =
        MailItem(id,account,uid=date,validity=validity,sender="Sender",senderEmail="sender@example.com",recipients="sales@example.com",subject="Subject",body="Body",date=date,unread=unread,localFolder=folder)

    @Test fun readingOneMessageDecreasesTheExistingNotification() {
        assertEquals(setOf("b"),SyncRules.pendingNotifications(setOf("a","b"),emptySet(),listOf(mail("a",false),mail("b"))))
    }
    @Test fun readingAllMessagesRemovesTheNotification() {
        assertTrue(SyncRules.pendingNotifications(setOf("a","b"),emptySet(),listOf(mail("a",false),mail("b",false))).isEmpty())
    }
    @Test fun deletedMessagesAndDisconnectedAccountsCannotLeaveStaleAlerts() {
        assertTrue(SyncRules.pendingNotifications(setOf("gone"),emptySet(),listOf(mail("other"))).isEmpty())
    }
    @Test fun historicalUnreadMailDoesNotBecomeANewAlert() {
        assertTrue(SyncRules.pendingNotifications(emptySet(),emptySet(),listOf(mail("historical"))).isEmpty())
    }
    @Test fun laterArrivalIsMergedWithStillUnreadAlerts() {
        assertEquals(setOf("a","c"),SyncRules.pendingNotifications(setOf("a","b"),setOf("c"),listOf(mail("a"),mail("b",false),mail("c"))))
    }
    @Test fun sentAndDraftMessagesAreNotNewIncomingNotifications() {
        assertTrue(SyncRules.pendingNotifications(emptySet(),setOf("a","b"),listOf(mail("a",folder="sent"),mail("b",folder="drafts"))).isEmpty())
    }
    @Test fun firstSyncAndUidValidityResetNeverNotifyAboutWholeHistory() {
        assertTrue(SyncRules.newUnreadIds(emptyList(),listOf(mail("a")),false).isEmpty())
        assertTrue(SyncRules.newUnreadIds(listOf(mail("a",validity=1)),listOf(mail("b",validity=2)),true).isEmpty())
    }
    @Test fun refreshOnlyNotifiesForNewUnreadUids() {
        assertEquals(setOf("b"),SyncRules.newUnreadIds(listOf(mail("a")),listOf(mail("a"),mail("b"),mail("c",false)),true))
    }
    @Test fun selectedMailboxesAreProcessedWithoutTouchingUnselectedMailboxes() {
        val job=HistoryDownload("job",listOf("b"),"running")
        assertEquals("b",SyncRules.nextHistoryAccount(job,listOf(account("a"),account("b")),emptyMap(),emptySet()))
    }
    @Test fun finishedAndDisconnectedMailboxesAreSkipped() {
        val job=HistoryDownload("job",listOf("removed","a","b"),"running")
        assertEquals("b",SyncRules.nextHistoryAccount(job,listOf(account("a"),account("b")),mapOf("a" to InboxHistory(200,false)),emptySet()))
    }
    @Test fun oneFailingMailboxDoesNotPreventOthersFromLoading() {
        val job=HistoryDownload("job",listOf("a","b"),"running")
        assertEquals("b",SyncRules.nextHistoryAccount(job,listOf(account("a"),account("b")),emptyMap(),setOf("a")))
    }
    @Test fun pauseAndCompletionStopHistoryRequests() {
        listOf("paused","done","idle").forEach{status->assertNull(SyncRules.nextHistoryAccount(HistoryDownload("job",listOf("a"),status),listOf(account("a")),emptyMap(),emptySet()))}
    }
    @Test fun aWaitingJobCanContinueAfterNetworkRecovery() {
        assertEquals("a",SyncRules.nextHistoryAccount(HistoryDownload("job",listOf("a"),"waiting"),listOf(account("a")),emptyMap(),emptySet()))
    }
    @Test fun pollingSettingsCannotRequestAnUnsupportedBackgroundInterval() {
        assertEquals(PollSettings(1,15),PollSettings.normalized(-1,1))
        assertEquals(PollSettings(5,0),PollSettings.normalized(5,0))
        assertEquals(PollSettings(60,120),PollSettings.normalized(60,120))
    }
    @Test fun editingPasswordDisplayNameOrSmtpPreservesIncomingCache() {
        val old=account("a")
        assertTrue(SyncRules.sameInbox(old,old.copy(name="Work",senderName="Sales",secret="new",smtpHost="out.example.com",imapPort=143,imapStartTls=true)))
        assertTrue(SyncRules.sameInbox(old,old.copy(imapHost="IMAP.EXAMPLE.COM")))
    }
    @Test fun anotherImapServerOrLoginRequiresANewIncomingCache() {
        val old=account("a")
        assertFalse(SyncRules.sameInbox(old,old.copy(imapHost="new.example.com")))
        assertFalse(SyncRules.sameInbox(old,old.copy(username="other@example.com")))
    }
    @Test fun editingAnAccountCanRetainThePasswordButAddingCannotOmitIt() {
        assertNull(MailRules.validateAccount(account("a"),"",passwordRequired=false))
        assertNotNull(MailRules.validateAccount(account("a"),""))
        assertNotNull(MailRules.validateAccount(account("a").copy(imapHost="https://example.com"),"",passwordRequired=false))
    }
    @Test fun returningToAnEmailKeepsItsAnchorWhenNewMessagesAreInsertedBeforeIt() {
        assertEquals(3,InboxPosition("b",1,37).indexIn(listOf("new2","new1","a","b","c")))
    }
    @Test fun aListPinnedToTheTopShowsTheNewFirstMessage() {
        assertEquals(0,InboxPosition("old",0,0).indexIn(listOf("new","old")))
    }
    @Test fun partiallyScrolledFirstMessageIsAnchoredInsteadOfJumping() {
        assertEquals(1,InboxPosition("old",0,12).indexIn(listOf("new","old")))
    }
    @Test fun removedAnchorFallsBackToTheClosestRemainingIndex() {
        assertEquals(1,InboxPosition("removed",25,30).indexIn(listOf("a","b")))
        assertEquals(0,InboxPosition("removed",25,30).indexIn(emptyList()))
    }
    @Test fun unreadFolderUsesOnlyTheCurrentlySelectedMailbox() {
        val result=MailRules.filter(listOf(mail("a1",account="a"),mail("a2",false,account="a"),mail("b1",account="b")),"a",MailFolder.UNREAD,"")
        assertEquals(listOf("a1"),result.map{it.id})
    }
    @Test fun unreadFolderCanShowAllMailboxesAndExcludesDrafts() {
        val result=MailRules.filter(listOf(mail("a",account="a",date=1),mail("b",account="b",date=2),mail("draft",folder="drafts")),null,MailFolder.UNREAD,"")
        assertEquals(listOf("b","a"),result.map{it.id})
    }
    @Test fun readingAMessageRemovesItFromUnreadButNotFromInbox() {
        val messages=listOf(mail("a",false),mail("b"))
        assertEquals(listOf("b"),MailRules.filter(messages,null,MailFolder.UNREAD,"").map{it.id})
        assertEquals(2,MailRules.filter(messages,null,MailFolder.INBOX,"").size)
    }
    @Test fun unreadSearchCombinesQueryAndReadStatus() {
        val messages=listOf(mail("a").copy(subject="Invoice"),mail("b",false).copy(subject="Invoice"),mail("c").copy(subject="Other"))
        assertEquals(listOf("a"),MailRules.filter(messages,null,MailFolder.UNREAD,"invoice").map{it.id})
    }
    @Test fun thousandsOfEmailsLoadInSmallBatchesWithoutGapsOrDuplicates() {
        var range=InboxPaging.latest(5137,20)!!
        val loaded=range.toMutableList()
        var batches=1
        while(true){range=InboxPaging.older(range.first,20)?:break;loaded+=range.toList();batches++}
        assertEquals(257,batches)
        assertEquals(5137,loaded.size)
        assertEquals((1..5137).toSet(),loaded.toSet())
    }
}
