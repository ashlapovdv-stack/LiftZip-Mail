package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactAddressesTest {
    @Test fun normalizesAndDeduplicatesAddresses() {
        assertEquals(listOf("User@Example.com", "other@example.com"),
            ContactAddresses.clean(listOf(" User@Example.com ", "user@example.com", "", "other@example.com")))
    }

    @Test fun matchesNameAndAnyAddress() {
        val contacts = listOf(
            MailContact("1", "Иван Петров", listOf("ivan@example.com", "work@company.ru")),
            MailContact("2", "Мария", listOf("maria@example.com"))
        )
        assertEquals(listOf("1"), ContactAddresses.suggestions(contacts, "COMPANY").map { it.id })
        assertEquals(listOf("1"), ContactAddresses.suggestions(contacts, "Петров").map { it.id })
        assertEquals(emptyList<MailContact>(), ContactAddresses.suggestions(contacts, " ").toList())
    }
}
