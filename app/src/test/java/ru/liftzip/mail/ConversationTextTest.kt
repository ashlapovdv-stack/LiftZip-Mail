package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ConversationTextTest {
    @Test fun quotedHistoryHidesInvisibleObjectsButPreservesTheOriginal() {
        val body="Новый ответ\n\nOn 28 Oct 2026, Person <a@example.com> wrote:\n> Текст\uFFFC\u200B\u00A0письма"
        val parsed=ConversationText.parse(body)
        assertEquals("Текст письма",parsed.messages.single().body)
        assertEquals(body,parsed.original)
    }
    @Test fun phoneExampleSeparatesNearestReplyAndOlderReply() {
        val body="Новый ответ\n\nчетверг, 29 октября 2026г., 16:47\n+09:00 от Дмитрий sales@example.com :\n\n> Предыдущий ответ\n>\n> ────────────────────\n> Клиент пишет:\n> Первый вопрос\n>"
        val c=ConversationText.parse(body)
        assertEquals("Новый ответ",c.current)
        assertEquals(2,c.messages.size)
        assertEquals("Дмитрий sales@example.com",c.messages[0].sender)
        assertTrue(c.messages[0].date!!.contains("+09:00"))
        assertEquals("Предыдущий ответ",c.messages[0].body)
        assertEquals("Клиент",c.messages[1].sender)
        assertNull(c.messages[1].date)
        assertEquals("Первый вопрос",c.messages[1].body)
        assertEquals(body,c.original)
    }
    @Test fun mailRuUnwrappedAttributionRetainsTimezoneAndSender() {
        val c=ConversationText.parse("Спасибо\n\nсреда, 28 октября 2026г., 09:38 +09:00\nот Иван <client@example.com> :\n> Заказ")
        assertEquals("Спасибо",c.current)
        assertEquals("Иван <client@example.com>",c.messages.single().sender)
        assertEquals("Заказ",c.messages.single().body)
    }
    @Test fun englishAndRussianRepliesHaveSeparateMetadata() {
        val english=ConversationText.parse("Thanks\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Old")
        assertEquals("Person <a@example.com>",english.messages.single().sender)
        assertEquals("Wed, 28 Oct 2026",english.messages.single().date)
        val russian=ConversationText.parse("Принято\n\n28 октября 2026 г. Клиент <a@example.com> писал(а):\n> Старое")
        assertEquals("Клиент <a@example.com>",russian.messages.single().sender)
        assertEquals("28 октября 2026 г.",russian.messages.single().date)
    }
    @Test fun wrappedEnglishAttributionDoesNotBecomeTheMessageBody() {
        val c=ConversationText.parse("Thanks\n\nOn Wed, 28 Oct 2026, Person\n<a@example.com> wrote:\n> Previous")
        assertEquals("Person <a@example.com>",c.messages.single().sender)
        assertEquals("Previous",c.messages.single().body)
    }
    @Test fun nestedAppRepliesKeepOrderAndStripQuoteMarkers() {
        val c=ConversationText.parse("Сегодня\n\n──────────\nВторой пишет:\nВчера\n\n──────────\nПервый пишет:\nПозавчера")
        assertEquals(listOf("Второй","Первый"),c.messages.map{it.sender})
        assertEquals(listOf("Вчера","Позавчера"),c.messages.map{it.body})
    }
    @Test fun unattributedQuoteDoesNotInventDateOrSender() {
        val c=ConversationText.parse("Ответ\n\n> Вопрос\n> Вторая строка")
        assertNull(c.messages.single().sender)
        assertNull(c.messages.single().date)
        assertEquals("Вопрос\nВторая строка",c.messages.single().body)
    }
    @Test fun inlineAnswersAreNotHiddenOrReordered() {
        val body="См. ниже\nOn 28 Oct 2026, Person wrote:\n> Вопрос\nМой ответ"
        val c=ConversationText.parse(body)
        assertEquals(body,c.current)
        assertTrue(c.messages.isEmpty())
    }
    @Test fun entirelyQuotedMessageStillShowsItsTextImmediately() {
        val body="> Ответ\n> Вторая строка"
        val c=ConversationText.parse(body)
        assertEquals(body,c.current)
        assertTrue(c.messages.isEmpty())
    }
    @Test fun proseMentioningTheDayAndAuthorDoesNotSplit() {
        val body="Нужно 2 детали\nсреда, 28 октября 2026\nИнженер пишет: проверить заказ."
        assertTrue(ConversationText.parse(body).messages.isEmpty())
    }
    @Test fun outlookHeadersAreReadableAndOriginalIsRetainedExactly() {
        val body="Ответ\r\n\r\n-----Original Message-----\r\nFrom: Person <a@example.com>\r\nSent: 28 October 2026 15:00\r\nTo: sales@example.com\r\nSubject: Order\r\n\r\nOld body"
        val c=ConversationText.parse(body)
        assertEquals("Person <a@example.com>",c.messages.single().sender)
        assertEquals("28 October 2026 15:00",c.messages.single().date)
        assertEquals("Old body",c.messages.single().body)
        assertEquals(body,c.original)
    }
    @Test fun identityUsesExactAddressesNotMatchingNamesOrSubstrings() {
        val m=QuotedMessage("Дмитрий <Sales@Example.com>",null,"")
        assertTrue(m.isOwn(listOf("sales@example.com")))
        assertFalse(m.isOwn(listOf("other-sales@example.com")))
        assertFalse(QuotedMessage("Дмитрий",null,"").isOwn(listOf("sales@example.com")))
    }
    @Test fun missingEnglishAddressKeepsOriginalAttributionWithoutInventedSender() {
        val m=ConversationText.parse("OK\nOn 28 Oct 2026, Person wrote:\n> Old").messages.single()
        assertNull(m.sender)
        assertNull(m.date)
        assertEquals("On 28 Oct 2026, Person wrote:",m.attribution)
    }
    @Test fun deepNestingIsBoundedAndOriginalCanAlwaysBeRecovered() {
        var body="Самое старое"
        repeat(50){body="Ответ $it\n\n──────────\nАвтор $it пишет:\n$body"}
        val c=ConversationText.parse(body)
        assertTrue(c.messages.size<=33)
        assertEquals(body,c.original)
        assertTrue(c.messages.last().body.contains("Самое старое"))
    }
}
