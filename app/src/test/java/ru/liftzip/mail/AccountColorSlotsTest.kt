package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class AccountColorSlotsTest {
    private fun account(id: String,slot: Int=-1)=MailAccount(id,id,"$id@example.com",provider=Provider.CUSTOM,imapHost="imap.example.com",smtpHost="smtp.example.com",colorSlot=slot)
    @Test fun migrationAssignsDifferentColorsAndDoesNotStealExistingColors() {
        val assigned=AccountColorSlots.assign(listOf(account("old"),account("existing",0),account("other")))
        assertEquals(0,assigned[1].colorSlot)
        assertEquals(3,assigned.map{it.colorSlot}.toSet().size)
        assertEquals(assigned,AccountColorSlots.assign(assigned))
    }
    @Test fun newAccountsNeverChangeExistingSlotsAndReuseAFreeOne() {
        val existing=listOf(account("a",0),account("b",4),account("c",2))
        assertEquals(1,AccountColorSlots.next(existing.map{it.colorSlot}))
        assertEquals(existing,AccountColorSlots.assign(existing))
        assertEquals(100,AccountColorSlots.assign((1..100).map{account("a$it")}).map{it.colorSlot}.toSet().size)
    }
}
