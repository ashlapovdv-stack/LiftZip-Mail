package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class SpamRulesTest {
    private fun mail()=MailItem("m","a",12,7,sender="Sender",senderEmail="s@example.com",recipients="a@example.com",subject="Subject",body="Body",date=1,unread=true)
    @Test fun serverSpecialUseTakesPriorityOverAnUnrelatedFolderNamedSpam() {
        val folders=listOf(TrashFolderInfo("Spam",emptySet()),TrashFolderInfo("Нежелательные",setOf("\\Junk")))
        assertEquals("Нежелательные",SpamRules.destination(folders,FolderRole.JUNK))
    }
    @Test fun localizedNestedFoldersAndOutlookNamesAreRecognized() {
        for(name in listOf("INBOX.Spam","INBOX/Спам","[Gmail]/Spam","Junk E-mail","Нежелательная почта")) {
            assertEquals(name,SpamRules.destination(listOf(TrashFolderInfo(name,emptySet())),FolderRole.JUNK))
        }
    }
    @Test fun unselectableFoldersAreNeverUsedAndMissingSpamHasNoInventedDestination() {
        val folders=listOf(TrashFolderInfo("INBOX",emptySet()),TrashFolderInfo("Spam",setOf("\\NoSelect","\\Junk")))
        assertNull(SpamRules.destination(folders,FolderRole.JUNK))
        assertEquals("INBOX",SpamRules.destination(folders,FolderRole.INBOX))
        assertNull(SpamRules.destination(listOf(TrashFolderInfo("Sent",emptySet())),FolderRole.INBOX))
    }
    @Test fun spamCanBeRestoredAndCannotBeReportedAsSpamAgain() {
        val junk=mail().copy(remoteFolder="Custom junk",localFolder="remote-junk")
        assertTrue(SpamRules.eligible(junk,true));assertFalse(SpamRules.eligible(junk,false))
        assertTrue(SpamRules.eligible(mail(),false));assertFalse(SpamRules.eligible(mail(),true))
    }
    @Test fun draftsSentLocalAndAlreadyMovingMessagesAreExcluded() {
        for(m in listOf(mail().copy(uid=0),mail().copy(validity=0),mail().copy(localFolder="remote-drafts"),mail().copy(localFolder="remote-sent"),mail().copy(localFolder="remote-trash"),mail().copy(pendingDelete=true)))
            assertFalse(SpamRules.eligible(m,false))
    }
    @Test fun queuedMovesAreProtectedFromStaleSyncAndDisappearFromTheSourceOnlyWhileActive() {
        for(kind in listOf(ActionKind.SPAM,ActionKind.RESTORE_INBOX)) {
            val action=MailAction("move","a","m",kind,"{}")
            assertEquals(setOf("m"),ActionRules.protectedIds(listOf(action)))
            assertTrue(FolderRules.display(ActionRules.overlay(listOf(mail()),listOf(action))).isEmpty())
            assertFalse(ActionRules.overlay(listOf(mail()),listOf(action.copy(status="failed"))).single().pendingDelete)
            assertEquals("pending",ActionRules.recovered(action.copy(status="running",lease="old"),"new").status)
        }
    }
}
