package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class ReplyTextTest {
    @Test fun foldsTheMailRuReplyReportedOnPhoneIncludingDateAndSender() {
        val body="Да пожалуйста\n\nсреда, 28 октября 2026г., 09:38 +09:00\nот Дмитрий sales@example.com :\n\n>\n> надо то надо это\n>"
        val parts=ReplyText.split(body)
        assertEquals("Да пожалуйста",parts.current)
        assertTrue(parts.canCollapse)
        assertTrue(parts.quoted!!.startsWith("среда,"))
        assertTrue(parts.quoted.contains("надо то надо это"))
    }
    @Test fun foldsEnglishAttributionAndQuotedBody() {
        assertEquals("Thanks",ReplyText.split("Thanks\n\nOn Wed, 28 Oct 2026, Person <a@example.com> wrote:\n> Previous text").current)
    }
    @Test fun handlesWrappedEnglishAttribution() {
        val parts=ReplyText.split("Thanks\n\nOn Wed, 28 Oct 2026, Person\n<a@example.com> wrote:\n> Previous text")
        assertEquals("Thanks",parts.current)
        assertTrue(parts.canCollapse)
    }
    @Test fun foldsAppGeneratedReplyDivider() {
        assertEquals("Ответ\n\n──────────\nДмитрий пишет:\nСтарое письмо".substringBefore("\n\n"),ReplyText.split("Ответ\n\n──────────\nДмитрий пишет:\nСтарое письмо").current)
    }
    @Test fun foldsTrailingGreaterThanBlockWithoutAttribution() {
        val parts=ReplyText.split("Новый ответ\n\n> старый текст\n> ещё строка\n")
        assertEquals("Новый ответ",parts.current)
        assertTrue(parts.canCollapse)
    }
    @Test fun inlineReplyAfterAQuoteRemainsVisible() {
        val body="См. ниже\n> Вопрос\nМой ответ"
        assertFalse(ReplyText.split(body).canCollapse)
        assertEquals(body,ReplyText.split(body).current)
    }
    @Test fun inlineReplyAfterAnAttributionRemainsVisible() {
        val body="См. ниже\nOn 28 Oct 2026, Person wrote:\n> Вопрос\nМой ответ"
        assertFalse(ReplyText.split(body).canCollapse)
    }
    @Test fun allQuotedMessageIsNotHiddenBehindAnEmptyScreen() {
        assertFalse(ReplyText.split("> Всё письмо\n> Вторая строка").canCollapse)
    }
    @Test fun normalDateOrUseOfTheWordWritesIsNotAQuote() {
        val body="Добрый день\nсреда, 28 октября 2026\nИнженер пишет: надо проверить заказ."
        assertEquals(body,ReplyText.split(body).current)
        assertFalse(ReplyText.split(body).canCollapse)
    }
    @Test fun windowsLineEndingsAndRussianAttributionAreSupported() {
        val parts=ReplyText.split("Принято\r\n\r\n28 октября 2026 г. Клиент <a@example.com> писал(а):\r\n> Старое")
        assertEquals("Принято",parts.current)
        assertTrue(parts.canCollapse)
    }
}
