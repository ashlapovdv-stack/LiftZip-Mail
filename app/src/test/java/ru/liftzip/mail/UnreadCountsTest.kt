package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class UnreadCountsTest {
    private fun mail(id: String="m",account: String="a",unread: Boolean=true,folder: String="inbox")=
        MailItem(id,account,sender="",senderEmail="",recipients="",subject="",body="",date=0,unread=unread,localFolder=folder)
    @Test fun countIsPerAccountAndIncomingOnlyIncludingOlderUnreadCache() {
        assertEquals(2,UnreadCounts.cached(listOf(mail(),mail("old",folder="unread-cache"),mail("other","b"),mail("draft",folder="drafts"),mail("sent",folder="sent"),mail("read",unread=false)),"a"))
    }
    @Test fun readingOneOfThousandsAdjustsTheServerTotal() {
        assertEquals(999,UnreadCounts.changed(1000,listOf(mail()),listOf(mail(unread=false))))
    }
    @Test fun repeatedMarkReadDoesNotDecrementCountAgain() {
        assertEquals(999,UnreadCounts.changed(999,listOf(mail(unread=false)),listOf(mail(unread=false))))
    }
    @Test fun markingSeveralUnreadAccountsForTheirPreviousState() {
        val before=listOf(mail("1",unread=false),mail("2",unread=false),mail("3"))
        assertEquals(12,UnreadCounts.changed(10,before,before.map{it.copy(unread=true)}))
    }
    @Test fun stalePollCannotRestoreAReadCountAfterLocalReading() {
        assertEquals(6,UnreadCounts.reconcile(7,listOf(mail()),listOf(mail(unread=false)),setOf("m")))
    }
    @Test fun pollFlagsAlreadyIncludingTheReadDoNotDoubleSubtract() {
        assertEquals(6,UnreadCounts.reconcile(6,listOf(mail(unread=false)),listOf(mail(unread=false)),setOf("m")))
    }
    @Test fun ordinaryPollUsesTheServerCountAndNeverGoesNegative() {
        assertEquals(1200,UnreadCounts.reconcile(1200,listOf(mail()),listOf(mail(unread=false)),emptySet()))
        assertEquals(0,UnreadCounts.changed(0,listOf(mail()),listOf(mail(unread=false))))
    }
}
