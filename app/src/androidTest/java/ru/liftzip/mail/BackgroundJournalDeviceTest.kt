package ru.liftzip.mail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jakarta.mail.MessagingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundJournalDeviceTest {
    @Test fun preservesDnsFailureAndTheOrderOfRecoveryAndOpening(): Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="test-background-journal-${UUID.randomUUID()}"
        val prefs=app.getSharedPreferences(name,Context.MODE_PRIVATE)
        val scope=CoroutineScope(SupervisorJob())
        try {
            val journal=BackgroundJournal({prefs},scope)
            journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.STARTED,1)
            journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.DNS_RETRY,1)
            journal.record(BackgroundSource.TEST,BackgroundStage.SUCCESS,1)
            journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.STARTED,2)
            journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.SUCCESS,2,1)
            journal.visibility(true)
            journal.visibility(true)
            journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.STARTED,1)
            journal.visibility(false)
            val before=withTimeout(10_000){journal.events.first{it.size==8}}
            assertEquals(listOf(BackgroundStage.STARTED,BackgroundStage.DNS_RETRY,BackgroundStage.SUCCESS,
                BackgroundStage.STARTED,BackgroundStage.SUCCESS,BackgroundStage.OPENED,BackgroundStage.STARTED,
                BackgroundStage.HIDDEN),before.map{it.stage})
            assertEquals(BackgroundSource.TEST,before[2].source)
            assertEquals(2,before[4].attempt)
            assertEquals(1,before[4].newUnread)
            assertFalse(before[4].visible)
            assertTrue(before[6].visible)

            val reopened=BackgroundJournal({prefs},scope)
            assertEquals(before,withTimeout(10_000){reopened.events.first{it.isNotEmpty()}})
        } finally {
            scope.cancel()
            app.deleteSharedPreferences(name)
        }
    }

    @Test fun keepsTheLatestEntriesAndRecognizesWrappedDnsErrors(): Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="test-background-journal-${UUID.randomUUID()}"
        val prefs=app.getSharedPreferences(name,Context.MODE_PRIVATE)
        val scope=CoroutineScope(SupervisorJob())
        try {
            assertTrue(MessagingException("IMAP",UnknownHostException("example.invalid")).isMailDnsFailure())
            assertFalse(MessagingException("IMAP",SocketTimeoutException()).isMailDnsFailure())
            val journal=BackgroundJournal({prefs},scope)
            val count=BackgroundJournal.LIMIT+4
            for(attempt in 1..count)journal.record(BackgroundSource.AUTOMATIC,BackgroundStage.STARTED,attempt)
            val events=withTimeout(15_000){journal.events.first{it.lastOrNull()?.attempt==count}}
            assertEquals((5..count).toList(),events.map{it.attempt})
        } finally {
            scope.cancel()
            app.deleteSharedPreferences(name)
        }
    }
}
