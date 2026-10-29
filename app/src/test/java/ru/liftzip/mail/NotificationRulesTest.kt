package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    private fun mail(uid: Long,unread: Boolean=true,account: String="a")=MailItem("$account:1:$uid",account,uid,1,sender="Sender",senderEmail="sender@example.com",recipients="me@example.com",subject="Subject $uid",body="Text $uid",date=uid,unread=unread)
    @Test fun aMessageCachedByHistoryBeforeThePollStillCreatesAnArrival() {
        val old=mail(100);val fresh=mail(101)
        val cached=SyncRules.historyPage(listOf(old),InboxPage(listOf(fresh),InboxHistory(2,false),1),emptySet())
        val result=NotificationRules.arrivals(ArrivalCursor(1,100,true),1,cached,true)
        assertEquals(setOf(fresh.id),result.arrived)
        assertEquals(101L,result.cursor.uid)
    }
    @Test fun anEmailSentToMyselfIsAnIncomingNotificationLikeAnyOther() {
        val own=mail(11).copy(senderEmail="me@example.com",recipients="me@example.com")
        assertEquals(setOf(own.id),NotificationRules.arrivals(ArrivalCursor(1,10,true),1,listOf(own),true).arrived)
    }
    @Test fun theFirstFullCheckSetsABaselineWithoutAlertingForOldMail() {
        val result=NotificationRules.arrivals(ArrivalCursor(),1,listOf(mail(100)),true)
        assertTrue(result.arrived.isEmpty());assertEquals(ArrivalCursor(1,100,true),result.cursor)
    }
    @Test fun aNewMessageAfterAnInitiallyEmptyInboxIsAnnounced() {
        val baseline=NotificationRules.arrivals(ArrivalCursor(),1,emptyList(),true)
        assertTrue(baseline.cursor.ready)
        assertEquals(setOf(mail(1).id),NotificationRules.arrivals(baseline.cursor,1,listOf(mail(1)),true).arrived)
    }
    @Test fun deletingAllMailDoesNotMoveTheUidCursorBackwards() {
        val cleared=NotificationRules.arrivals(ArrivalCursor(1,100,true),1,emptyList(),true)
        assertEquals(100L,cleared.cursor.uid)
        assertEquals(setOf(mail(110).id),NotificationRules.arrivals(cleared.cursor,1,listOf(mail(110)),true).arrived)
    }
    @Test fun aUidValidityResetCreatesANewSilentBaseline() {
        val next=mail(200).copy(validity=2,id="a:2:200")
        val result=NotificationRules.arrivals(ArrivalCursor(1,100,true),2,listOf(next),true)
        assertTrue(result.arrived.isEmpty());assertEquals(ArrivalCursor(2,200,true),result.cursor)
    }
    @Test fun anUnreadQueryAloneCannotTurnThousandsOfOldMessagesIntoNewAlerts() {
        val result=NotificationRules.arrivals(ArrivalCursor(),1,listOf(mail(100)),false)
        assertFalse(result.cursor.ready);assertTrue(result.arrived.isEmpty())
    }
    @Test fun anUnreadQueryFindsNewMailWithoutAnnouncingOldSparseUnread() {
        val old=mail(1).copy(localFolder="unread-cache");val fresh=mail(101).copy(localFolder="unread-cache")
        val arrival=NotificationRules.arrivals(ArrivalCursor(1,100,true),1,listOf(old,fresh),false)
        val plan=NotificationRules.plan(emptySet(),arrival.arrived,listOf(old,fresh),emptySet())
        assertEquals(listOf(fresh),plan.messages)
    }
    @Test fun aNewMessageAlreadyReadElsewhereAdvancesTheCursorWithoutAnAlert() {
        val result=NotificationRules.arrivals(ArrivalCursor(1,100,true),1,listOf(mail(101,false)),true)
        assertTrue(result.arrived.isEmpty());assertEquals(101L,result.cursor.uid)
    }
    @Test fun retryingAfterAnotherAccountFailedDoesNotNotifyTheSuccessfulAccountTwice() {
        val first=NotificationRules.arrivals(ArrivalCursor(1,100,true),1,listOf(mail(101)),true)
        assertTrue(NotificationRules.shouldRetry(SyncResult(first.arrived.size,listOf("Second account timed out"))))
        val retry=NotificationRules.arrivals(first.cursor,1,listOf(mail(101)),true)
        assertTrue(retry.arrived.isEmpty())
    }
    @Test fun notificationsWaitForPermissionAndCanBeDisplayedLaterWithoutAnotherArrival() {
        val message=mail(1)
        val denied=NotificationRules.plan(emptySet(),setOf(message.id),listOf(message),emptySet())
        assertFalse(NotificationAccess(permission=false).canPost)
        val permitted=NotificationRules.plan(denied.pending,emptySet(),listOf(message),denied.dismissed)
        assertTrue(NotificationAccess().canPost)
        assertEquals(listOf(message),permitted.messages)
    }
    @Test fun disablingEitherAppNotificationsOrTheMailChannelBlocksPosting() {
        assertFalse(NotificationAccess(appEnabled=false).canPost)
        assertFalse(NotificationAccess(channelEnabled=false).canPost)
        assertTrue(NotificationAccess(backgroundRestricted=true,batteryExempt=false).canPost)
    }
    @Test fun dismissingAnAlertDoesNotMarkTheEmailReadAndDoesNotResurfaceIt() {
        val message=mail(1)
        val plan=NotificationRules.plan(setOf(message.id),emptySet(),listOf(message),setOf(message.id))
        assertTrue(message.unread);assertTrue(plan.messages.isEmpty());assertEquals(setOf(message.id),plan.pending)
        assertEquals(setOf(message.id),plan.dismissed)
    }
    @Test fun updatingAPreviewDoesNotBringBackADismissedNotification() {
        val message=mail(1).copy(body="Full text just downloaded")
        assertTrue(NotificationRules.plan(setOf(message.id),emptySet(),listOf(message),setOf(message.id)).messages.isEmpty())
    }
    @Test fun aNewMailStillAppearsAfterTheOlderGroupWasDismissed() {
        val old=mail(1);val fresh=mail(2)
        val plan=NotificationRules.plan(setOf(old.id),setOf(fresh.id),listOf(old,fresh),setOf(old.id))
        assertEquals(listOf(fresh),plan.messages)
    }
    @Test fun readingMailRemovesItAndShrinksTheGroup() {
        val old=mail(1);val fresh=mail(2)
        val plan=NotificationRules.plan(setOf(old.id,fresh.id),emptySet(),listOf(old.copy(unread=false),fresh),emptySet())
        assertEquals(setOf(fresh.id),plan.pending);assertEquals(listOf(fresh),plan.messages)
    }
    @Test fun turningOldMailUnreadAgainDoesNotCreateANewArrival() {
        val message=mail(1)
        val read=NotificationRules.plan(setOf(message.id),emptySet(),listOf(message.copy(unread=false)),setOf(message.id))
        val unread=NotificationRules.plan(read.pending,emptySet(),listOf(message),read.dismissed)
        assertTrue(unread.messages.isEmpty())
    }
    @Test fun removedOrPausedMailboxesLoseTheirPendingCards() {
        val plan=NotificationRules.plan(setOf(mail(1).id),emptySet(),emptyList(),emptySet())
        assertTrue(plan.pending.isEmpty());assertTrue(plan.messages.isEmpty())
    }
    @Test fun multipleAccountsAreGroupedWithoutConfusingMatchingRemoteUids() {
        val a=mail(1,account="a");val b=mail(1,account="b")
        val plan=NotificationRules.plan(emptySet(),setOf(a.id,b.id),listOf(a,b),emptySet())
        assertEquals(2,plan.messages.size);assertEquals(setOf(a.id,b.id),plan.pending)
    }
    @Test fun aLargeBurstHasAnAccurateTotalAndBoundedIndividualCards() {
        val messages=(1L..100L).map{mail(it)}
        val plan=NotificationRules.plan(emptySet(),messages.map{it.id}.toSet(),messages,emptySet())
        assertEquals(100,plan.messages.size)
        assertEquals(40,plan.messages.take(NotificationRules.MAX_CHILDREN).size)
        assertEquals(100L,plan.messages.first().uid)
    }
    @Test fun notificationTextUsesTheCurrentReplyAndHidesAnOldQuote() {
        val message=mail(1).copy(body="New answer\n\n> old answer\n> more old text")
        assertEquals("New answer",NotificationRules.body(message))
    }
    @Test fun expandedTextIsLongerThanTheShortPreviewButStillBounded() {
        val message=mail(1).copy(body="x".repeat(5000))
        assertEquals(3000,NotificationRules.body(message).length)
        assertEquals(180,NotificationRules.shortBody(message).length)
    }
    @Test fun pendingTextIsExplicitAndAnEmptyMessageWithAttachmentsHasAMeaningfulLabel() {
        assertTrue(NotificationRules.body(mail(1).copy(contentCached=false)).contains("пока не загружен"))
        assertEquals("Письмо содержит вложения",NotificationRules.body(mail(1).copy(body="",attachments=listOf(Attachment("0","a.pdf","application/pdf",1)))))
    }
    @Test fun shortTextDoesNotKeepLineBreaksOrRepeatedWhitespace() {
        assertEquals("one two three",NotificationRules.shortBody(mail(1).copy(body="one\n\n two\t three")))
    }
}
