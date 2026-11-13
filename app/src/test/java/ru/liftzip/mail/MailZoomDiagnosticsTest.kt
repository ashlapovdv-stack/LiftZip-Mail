package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class MailZoomDiagnosticsTest {
    private class Scheduler: MailZoomDiagnosticScheduler {
        var pending: Runnable?=null
        var delayMillis: Long=-1
        override fun postDelayed(delayMillis: Long,task: Runnable){this.delayMillis=delayMillis;pending=task}
        override fun removeCallbacks(task: Runnable){if(pending===task)pending=null}
        fun runPending(){pending?.also{pending=null;it.run()}}
    }

    private fun log(scheduler: Scheduler=Scheduler(),recordLimit: Int=4096,historyLimit: Int=12000)=
        MailZoomDiagnosticLog({123L},scheduler,recordLimit,historyLimit)

    @Test fun disabledByDefaultDoesNotEvaluateLazyRecordsOrScheduleWork() {
        val scheduler=Scheduler();val log=log(scheduler);var evaluations=0
        assertFalse(log.enabled);assertFalse(log.isRecording)
        log.record{evaluations++;"must not be prepared"}
        log.start();log.record{evaluations++;"still disabled"}
        assertEquals(0,evaluations);assertTrue(log.snapshot().isEmpty());assertNull(scheduler.pending)
        log.setEnabled(true)
        log.record{evaluations++;"no active gesture"}
        assertEquals(0,evaluations);assertNull(scheduler.pending)
    }

    @Test fun enabledSessionsKeepBoundedCurrentAndRetainedHistory() {
        val scheduler=Scheduler();val log=log(scheduler,recordLimit=3,historyLimit=4)
        log.setEnabled(true);log.start()
        repeat(5){index->log.record{"event-$index"}}
        assertTrue(log.snapshot().size<=3)
        assertTrue(log.snapshot().any{it.endsWith("event-4")})
        log.finish();assertEquals(900L,scheduler.delayMillis);scheduler.runPending()
        val retained=log.snapshot()
        assertTrue(retained.size<=4)
        assertTrue(retained.any{it.contains("gestureEnd dropped=")})
        assertFalse(log.isRecording);assertFalse(log.hasPendingFlush);assertNull(scheduler.pending)
    }

    @Test fun disablingAndClearingEndSessionsAndCancelDelayedCompletion() {
        val scheduler=Scheduler();val log=log(scheduler)
        log.setEnabled(true);log.start();log.record{"before disable"};log.finish()
        assertNotNull(scheduler.pending)
        log.setEnabled(false)
        assertFalse(log.enabled);assertFalse(log.isRecording);assertFalse(log.hasPendingFlush);assertNull(scheduler.pending)
        assertTrue(log.snapshot().any{it.contains("reason=disabled")})
        var evaluations=0;log.record{evaluations++;"disabled"};assertEquals(0,evaluations)

        log.setEnabled(true);log.start();log.record{"before clear"};log.finish();assertNotNull(scheduler.pending)
        log.clear()
        assertTrue(log.snapshot().isEmpty());assertFalse(log.isRecording);assertFalse(log.hasPendingFlush);assertNull(scheduler.pending)
        log.start();log.record{"after clear"};log.finish();scheduler.runPending()
        assertTrue(log.snapshot().all{it.contains(" g=1 ")})
    }

    @Test fun rapidConsecutiveGesturesClosePreviousSessionBeforeStartingNext() {
        val scheduler=Scheduler();val log=log(scheduler)
        log.setEnabled(true)
        log.start();log.start() // Repeated pointer-down must not restart the same gesture.
        log.record{"first"};log.finish()
        log.start() // Supersedes only the completed gesture awaiting delayed retention.
        log.record{"second"};log.finish();scheduler.runPending()
        val lines=log.snapshot();val text=lines.joinToString("\n")
        assertEquals(2,lines.count{it.contains("gestureBegin")})
        assertTrue(text.contains("reason=superseded"))
        assertTrue(text.indexOf(" g=1 first")<text.indexOf(" g=2 gestureBegin"))
        assertTrue(text.indexOf(" g=2 gestureBegin")<text.indexOf(" g=2 second"))
        assertFalse(log.hasPendingFlush);assertNull(scheduler.pending)
    }
}
