package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSuggestionsTest {
    @Test fun usesLastRecipientToken() {
        assertEquals("pet",RecipientSuggestions.currentToken("first@example.com, pet"))
        assertEquals("first@example.com, peter@example.com",
            RecipientSuggestions.replaceToken("first@example.com, pet","peter@example.com"))
    }

    @Test fun savedContactsTakePriorityAndHistoryRemainsSeparate() {
        val saved = listOf(MailContact("1","Пётр",listOf("pet@example.com")))
        val mail = MailItem(id="1",accountId="a",sender="Петя",senderEmail="pet@example.com",
            recipients="other@example.com",subject="",body="",date=100)
        val suggestions = RecipientSuggestions.search(saved,listOf(mail),"pet")
        assertEquals(1,suggestions.size)
        assertEquals(true,suggestions.single().saved)
        assertEquals("Пётр",suggestions.single().label)
    }

    @Test fun suggestsRecentCorrespondentsWithoutSavingContacts() {
        val mail = MailItem(id="1",accountId="a",sender="Алексей",senderEmail="alex@example.com",
            recipients="friend@example.com",subject="",body="",date=100)
        val suggestions = RecipientSuggestions.search(emptyList(),listOf(mail),"friend")
        assertEquals(listOf("friend@example.com"),suggestions.map { it.email })
        assertEquals(false,suggestions.single().saved)
    }
}
