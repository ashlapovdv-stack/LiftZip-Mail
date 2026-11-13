package ru.liftzip.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalMessageFileTest {
    @Test fun keepsReadableRussianSubject() {
        assertEquals("Ответ по заказу.eml",OriginalMessageFile.name("Ответ по заказу"))
    }
    @Test fun removesPathsAndControlCharacters() {
        assertEquals("Re заказ 42.eml",OriginalMessageFile.name("Re: заказ/42\n"))
        assertEquals("Письмо.eml",OriginalMessageFile.name(" ... "))
    }
    @Test fun limitsFilenameLength() {
        assertEquals(104,OriginalMessageFile.name("a".repeat(300)).length)
    }
}
