package ru.liftzip.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFramesTest {
    @Test fun separatesTheFirstDrawAndRetainsTheWorstMissedDeadlines() {
        val frames = StartupFrames()
        val deadline = 8_000_000L
        frames.record(10, 90_000_000L, deadline, 12, 20, first = true, lost = 2)
        frames.record(20, 7_000_000L, deadline, 12, 20, first = false, lost = 0)
        for (index in 1L..7L) {
            frames.record(100 + index, (8 + index) * 1_000_000, deadline, 2_000_000, 3_000_000, first = false, lost = 0)
        }

        val report = frames.describe()
        assertTrue(report.contains("Получено кадров: 9. Опоздало без учёта первого кадра: 7. Пропущено отчётов: 2."))
        assertTrue(report.contains("Первый кадр: 90.0 мс."))
        assertFalse(report.contains("+101 мс:"))
        assertTrue(report.contains("+107 мс: кадр 15.0 мс из 8.0 мс; разметка 2.0 мс, отрисовка 3.0 мс."))
    }
}
