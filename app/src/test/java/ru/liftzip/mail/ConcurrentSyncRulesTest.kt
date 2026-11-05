package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ConcurrentSyncRulesTest {
    private fun account(id: String,enabled: Boolean=true)=MailAccount(id=id,name=id,email="$id@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com",enabled=enabled)
    private fun mail(id: String,uid: Long=10,validity: Long=1,accountId: String="a")=MailItem(id,accountId,uid,validity,sender="Sender",senderEmail="sender@example.com",recipients="recipient@example.com",subject="Subject",body="full text",date=uid,unread=true)

    @Test fun aCompletedPollKeepsOlderMessagesLoadedByHistoryMeanwhile() {
        val captured=listOf(mail("latest",100))
        val current=listOf(mail("older",90))+captured
        val result=SyncRules.checkedPage(captured,current,InboxPage(captured,InboxHistory(200,true)),emptySet())
        assertEquals(setOf("older","latest"),result.map{it.id}.toSet())
    }
    @Test fun aPollCannotReplaceTextDownloadedAfterItsRequestStarted() {
        val header=mail("a").copy(body="",contentCached=false)
        val full=header.copy(body="saved full text",contentCached=true,attachments=listOf(Attachment("0","file.pdf","application/pdf",100)))
        val result=SyncRules.checkedPage(listOf(header),listOf(full),InboxPage(listOf(header),InboxHistory(1,false)),emptySet()).single()
        assertEquals(full.body,result.body);assertEquals(full.attachments,result.attachments);assertTrue(result.contentCached)
    }
    @Test fun readAndStarChangesMadeDuringAPollArePreserved() {
        val old=mail("a");val now=old.copy(unread=false,starred=true)
        val result=SyncRules.checkedPage(listOf(old),listOf(now),InboxPage(listOf(old),InboxHistory(1,false)),setOf("a")).single()
        assertFalse(result.unread);assertTrue(result.starred)
    }
    @Test fun evenANewlyLoadedMessageCanHaveProtectedFlags() {
        val old=mail("old",1);val fresh=mail("fresh",2)
        val result=SyncRules.checkedPage(listOf(old),listOf(old,fresh.copy(unread=false)),InboxPage(listOf(old,fresh),InboxHistory(2,false)),setOf("fresh"))
        assertFalse(result.single{it.id=="fresh"}.unread)
    }
    @Test fun remoteFlagsAreStillUpdatedWhenTheUserDidNotChangeThem() {
        val old=mail("a")
        val result=SyncRules.checkedPage(listOf(old),listOf(old),InboxPage(listOf(old.copy(unread=false)),InboxHistory(1,false)),emptySet()).single()
        assertFalse(result.unread)
    }
    @Test fun aPollRemovesMessagesConfirmedDeletedInsideItsOriginalWindow() {
        val old=listOf(mail("deleted",1),mail("present",2))
        val result=SyncRules.checkedPage(old,old,InboxPage(listOf(old[1]),InboxHistory(1,false)),emptySet())
        assertEquals(listOf("present"),result.map{it.id})
    }
    @Test fun aMailboxEpochChangeDiscardsAllOldCachedUids() {
        val old=listOf(mail("old",validity=1))
        val result=SyncRules.checkedPage(old,old,InboxPage(listOf(mail("new",validity=2)),InboxHistory(1,false)),emptySet())
        assertEquals(listOf("new"),result.map{it.id})
    }
    @Test fun anEmptyRemoteMailboxClearsItsLocalIncomingWindow() {
        assertTrue(SyncRules.checkedPage(listOf(mail("old")),listOf(mail("old")),InboxPage(emptyList(),InboxHistory(0,false),1),emptySet()).isEmpty())
    }
    @Test fun aHistoryPageAddsItsBatchWithoutDroppingTheCurrentWindow() {
        val current=listOf(mail("latest",100))
        val result=SyncRules.historyPage(current,InboxPage(listOf(mail("older",90)),InboxHistory(100,true)),emptySet())
        assertEquals(setOf("latest","older"),result.map{it.id}.toSet())
    }
    @Test fun reachingTheBeginningDoesNotClearHistoryWhenThereIsNoNextBatch() {
        val current=listOf(mail("a"))
        assertEquals(current,SyncRules.historyPage(current,InboxPage(emptyList(),InboxHistory(1,false),1),emptySet()))
    }
    @Test fun aFreshUnreadResultCannotUndoAConcurrentRead() {
        val old=mail("a");val now=old.copy(unread=false)
        assertFalse(SyncRules.unreadPage(listOf(old),listOf(now),UnreadPage(1,listOf(old),1),setOf("a")).single().unread)
    }
    @Test fun anUnreadQueryDoesNotMarkMailThatArrivedAfterItStartedAsRead() {
        val fresh=mail("fresh")
        assertTrue(SyncRules.unreadPage(emptyList(),listOf(fresh),UnreadPage(1,emptyList(),1),emptySet()).single().unread)
    }
    @Test fun bodyDownloadsKeepCurrentReadAndStarFlags() {
        val current=mail("a").copy(body="",contentCached=false,unread=false,starred=true)
        val full=mail("a").copy(body="downloaded")
        val result=SyncRules.contentBatch(listOf(current),ContentBatch(listOf(full),emptySet())).single()
        assertEquals("downloaded",result.body);assertFalse(result.unread);assertTrue(result.starred)
    }
    @Test fun missingBodiesConfirmedDeletedAreRemovedWithoutAffectingOtherMessages() {
        val result=SyncRules.contentBatch(listOf(mail("gone"),mail("kept")),ContentBatch(emptyList(),setOf("gone")))
        assertEquals(listOf("kept"),result.map{it.id})
    }
    @Test fun historyIsIncompleteUntilBodiesOfItsKnownHeadersAreSaved() {
        assertTrue(SyncRules.needsHistory("a",listOf(mail("a").copy(contentCached=false)),InboxHistory(1,false)))
        assertFalse(SyncRules.needsHistory("a",listOf(mail("a")),InboxHistory(1,false)))
        assertTrue(SyncRules.needsHistory("a",listOf(mail("a")),InboxHistory(2,true)))
    }
    @Test fun pausedAccountsDisappearFromCombinedFoldersButTheirCachedViewRemains() {
        val messages=listOf(mail("a",accountId="a"),mail("b",accountId="b"))
        val accounts=listOf(account("a"),account("b",false))
        assertEquals(listOf("a"),SyncRules.activeMessages(messages,accounts,null).map{it.id})
        assertEquals(listOf("b"),SyncRules.activeMessages(messages,accounts,"b").map{it.id})
    }
    @Test fun pausedAccountsAreSkippedByHistoryAndDefaultSenderSelection() {
        val accounts=listOf(account("a",false),account("b"))
        assertEquals("b",SyncRules.nextHistoryAccount(HistoryDownload("job",listOf("a","b"),"running"),accounts,emptyMap(),emptySet()))
        assertEquals("b",SenderRules.forNewMessage(accounts,"a","a"))
        assertNull(SenderRules.forNewMessage(listOf(account("a",false)),"a","a"))
    }
    @Test fun headerPollingPreservesTheCachedHtmlAndItsFormatVersion() {
        val full=mail("a").copy(body="Full text",htmlBody="<p>Full HTML</p>",contentVersion=1)
        val header=full.copy(body="",htmlBody="",contentVersion=0,contentCached=false,previewOnly=true)
        val result=SyncRules.checkedPage(listOf(full),listOf(full),InboxPage(listOf(header),InboxHistory(1,false)),emptySet()).single()
        assertEquals(full.htmlBody,result.htmlBody);assertEquals(1,result.contentVersion)
    }
    @Test fun aNewFormatCanReplaceAnOldCachedTextWithoutLosingTheHtmlAgain() {
        val old=mail("a").copy(body="Old text",contentVersion=0)
        val fresh=old.copy(body="Clean text",htmlBody="<p>Clean HTML</p>",contentVersion=1)
        val result=SyncRules.checkedPage(listOf(old),listOf(old),InboxPage(listOf(fresh),InboxHistory(1,false)),emptySet()).single()
        assertEquals(fresh.htmlBody,result.htmlBody);assertEquals("Clean text",result.body);assertEquals(1,result.contentVersion)
    }

}
