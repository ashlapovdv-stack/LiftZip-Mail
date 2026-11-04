package ru.liftzip.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class HeaderTextTest {
    @Test fun decodesTheEncodedRecipientReportedOnPhone() {
        val header="=?UTF-8?B?0JTQvNC40YLRgNC40Lk=?= <sales@example.com>"
        assertEquals("Дмитрий <sales@example.com>",HeaderText.recipients(header))
    }
    @Test fun decodesRecipientsFetchedFromMimeEnvelope() {
        val raw="To: =?UTF-8?B?0JTQvNC40YLRgNC40Lk=?= <sales@example.com>\r\nSubject: Test\r\n\r\nBody"
        val message=MimeMessage(Session.getInstance(Properties()),raw.byteInputStream())
        assertEquals("Дмитрий <sales@example.com>",HeaderText.addresses(message.getRecipients(Message.RecipientType.TO)))
    }
    @Test fun retainsSeveralAddressesAndNamesContainingComma() {
        val display=HeaderText.recipients("\"Склад, Якутск\" <store@example.com>, =?UTF-8?B?0JTQvNC40YLRgNC40Lk=?= <sales@example.com>")
        assertTrue(display.contains("\"Склад, Якутск\" <store@example.com>"))
        assertTrue(display.contains("Дмитрий <sales@example.com>"))
        assertFalse(display.contains("=?UTF-8"))
    }
    @Test fun plainAddressAndEmptyHeaderRemainUsable() {
        assertEquals("test@example.com",HeaderText.recipients("test@example.com"))
        assertEquals("",HeaderText.recipients(""))
        assertEquals("",HeaderText.addresses(null))
    }
    @Test fun unknownCharacterSetDoesNotCrashOpeningCachedMail() {
        assertEquals("=?unknown-charset?Q?Name?=",HeaderText.decode("=?unknown-charset?Q?Name?="))
    }
    @Test fun serverDraftEditorKeepsAllRecipientsWithQuotedCommasAndEncodedNames() {
        val source="\"Иванов, Иван\" <ivan@example.com>, =?UTF-8?B?0JTQvNC40YLRgNC40Lk=?= <dmitry@example.com>"
        assertEquals("ivan@example.com, dmitry@example.com",HeaderText.editableRecipients(source))
    }
    @Test fun anInvalidDraftRecipientIsNotSilentlyDropped() {
        val source="good@example.com, broken <not-an-email>"
        assertEquals(source,HeaderText.editableRecipients(source))
    }

}
