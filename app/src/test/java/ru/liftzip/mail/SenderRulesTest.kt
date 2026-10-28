package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class SenderRulesTest {
    private fun account(id: String)=MailAccount(id=id,name=id,email="$id@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com")
    private val accounts=listOf(account("work"),account("personal"))
    @Test fun explicitDefaultWinsEvenWhileViewingAnotherMailbox() { assertEquals("work",SenderRules.forNewMessage(accounts,"work","personal")) }
    @Test fun withoutDefaultTheOpenedMailboxIsUsed() { assertEquals("personal",SenderRules.forNewMessage(accounts,null,"personal")) }
    @Test fun combinedInboxFallsBackToFirstConnectedMailbox() { assertEquals("work",SenderRules.forNewMessage(accounts,null,null)) }
    @Test fun removedDefaultDoesNotLeaveNewMessagesWithoutSender() { assertEquals("personal",SenderRules.forNewMessage(accounts,"deleted","personal")) }
    @Test fun noMailboxRequiresConnectingAnAccount() { assertNull(SenderRules.forNewMessage(emptyList(),"deleted",null)) }
}
