package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class TrashRulesTest {
    private fun mail(uid: Long=12,validity: Long=9,folder: String="inbox",unread: Boolean=true,delivery: String="")=
        MailItem("a:$validity:$uid","a",uid,validity,sender="S",senderEmail="s@example.com",recipients="r@example.com",subject="Subject",body="Body",date=0,localFolder=folder,unread=unread,delivery=delivery)
    @Test fun specialUseTrashWinsOverAnOrdinaryFolderNamedTrash() {
        assertEquals("[Gmail]/Корзина",TrashRules.destination(listOf(TrashFolderInfo("Trash",emptySet()),TrashFolderInfo("[Gmail]/Корзина",setOf("\\Trash")))))
    }
    @Test fun localizedLegacyTrashCanBeFoundWithoutSpecialUse() {
        assertEquals("Удалённые",TrashRules.destination(listOf(TrashFolderInfo("INBOX",emptySet()),TrashFolderInfo("Удалённые",emptySet()))))
        assertEquals("INBOX.Trash",TrashRules.destination(listOf(TrashFolderInfo("INBOX.Trash",emptySet()))))
    }
    @Test fun noSelectAndInboxAreNeverDeletionDestinations() {
        assertNull(TrashRules.destination(listOf(TrashFolderInfo("Trash",setOf("\\NoSelect","\\Trash")),TrashFolderInfo("INBOX",setOf("\\Trash")))))
    }
    @Test fun anUnknownFolderIsNeverAssumedToBeTrash() {
        assertNull(TrashRules.destination(listOf(TrashFolderInfo("All Mail",setOf("\\All")),TrashFolderInfo("Spam",setOf("\\Junk")))))
    }
    @Test fun atomicMoveIsPreferred() {
        assertEquals(TrashMethod.MOVE,TrashRules.method(true,true))
        assertEquals(TrashMethod.MOVE,TrashRules.method(true,false))
    }
    @Test fun fallbackRequiresTargetedUidExpunge() {
        assertEquals(TrashMethod.COPY_UID_EXPUNGE,TrashRules.method(false,true))
        assertNull(TrashRules.method(false,false))
    }
    @Test fun remoteDeletionRequiresInboxAndAnExactUidEpoch() {
        assertTrue(TrashRules.remote(mail()))
        assertTrue(TrashRules.remote(mail(folder="unread-cache")))
        assertFalse(TrashRules.remote(mail(validity=0)))
        assertFalse(TrashRules.remote(mail(uid=0)))
        assertFalse(TrashRules.remote(mail(folder="sent")))
    }
    @Test fun onlyLocalDraftsAndSentMailCanBeDeletedWithoutAServer() {
        assertTrue(TrashRules.local(mail(uid=0,folder="drafts")))
        assertTrue(TrashRules.local(mail(uid=0,folder="sent")))
        assertFalse(TrashRules.local(mail(uid=0,folder="inbox")))
        assertFalse(TrashRules.local(mail(folder="sent")))
        assertFalse(TrashRules.local(mail(uid=0,folder="drafts",delivery="sending")))
    }
    @Test fun unreadCountOnlyLosesRemovedUnreadInboxMessages() {
        assertEquals(19,TrashRules.unreadAfter(20,listOf(mail(),mail(unread=false),mail(uid=0,folder="drafts"))))
    }
    @Test fun outdatedUnreadCountCannotBecomeNegative() {
        assertEquals(0,TrashRules.unreadAfter(0,listOf(mail())))
    }
}
