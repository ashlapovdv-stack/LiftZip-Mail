package ru.liftzip.mail

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import java.util.ArrayDeque

/** Pure session storage, independently testable without Android main-loop stubs. */
internal interface MailZoomDiagnosticScheduler {
    fun postDelayed(delayMillis: Long,task: Runnable)
    fun removeCallbacks(task: Runnable)
}

internal class MailZoomDiagnosticLog(
    private val clock: ()->Long,
    private val scheduler: MailZoomDiagnosticScheduler,
    private val recordLimit: Int=4096,
    private val historyLimit: Int=12000
) {
    private val records=ArrayDeque<String>()
    private val history=ArrayDeque<String>()
    private var active=false
    private var flushPending=false
    private var dropped=0
    private var generation=0
    private val flushTask=Runnable{completeSession("finished")}
    var enabled=false
        private set
    val isRecording: Boolean get()=enabled && active
    val hasPendingFlush: Boolean get()=flushPending

    init {require(recordLimit>0 && historyLimit>0)}

    fun setEnabled(value: Boolean) {
        if(enabled==value)return
        if(!value) {
            if(active)completeSession("disabled")
            else {scheduler.removeCallbacks(flushTask);flushPending=false}
        }
        enabled=value
    }

    inline fun record(event: ()->String) {if(isRecording)record(event())}

    fun record(event: String) {
        if(!isRecording)return
        if(records.size>=recordLimit){records.removeFirst();dropped++}
        records.addLast("t=${clock()} g=$generation $event")
    }

    fun start() {
        if(!enabled)return
        if(active) {
            if(!flushPending)return
            // Finish the previous gesture before assigning a new generation.
            completeSession("superseded")
        }
        active=true;generation++;dropped=0;records.clear()
        record("gestureBegin")
    }

    fun finish() {
        if(!isRecording)return
        scheduler.removeCallbacks(flushTask)
        flushPending=true
        scheduler.postDelayed(900L,flushTask)
    }

    fun clear() {
        scheduler.removeCallbacks(flushTask)
        history.clear();records.clear();active=false;flushPending=false;dropped=0;generation=0
    }

    fun snapshot(): List<String> = history.toMutableList().apply{if(active)addAll(records)}

    private fun completeSession(reason: String) {
        if(!active)return
        scheduler.removeCallbacks(flushTask)
        record("gestureEnd dropped=$dropped reason=$reason")
        active=false;flushPending=false
        while(records.isNotEmpty()) {
            val line=records.removeFirst()
            if(history.size>=historyLimit)history.removeFirst()
            history.addLast(line)
        }
        dropped=0
    }
}

/**
 * Opt-in debug-only, geometry-only trace. No email text, account identifiers or URLs.
 * Bounded records stay in memory; only an explicit snapshot exports them. No logcat flush.
 */
internal object MailZoomDiagnostics {
    private val handler by lazy(LazyThreadSafetyMode.NONE){Handler(Looper.getMainLooper())}
    private val scheduler=object: MailZoomDiagnosticScheduler {
        override fun postDelayed(delayMillis: Long,task: Runnable){handler.postDelayed(task,delayMillis)}
        override fun removeCallbacks(task: Runnable){handler.removeCallbacks(task)}
    }
    private val log=MailZoomDiagnosticLog({SystemClock.uptimeMillis()},scheduler)
    val isEnabled: Boolean get()=BuildConfig.DEBUG && log.enabled
    @PublishedApi internal val isRecording: Boolean get()=BuildConfig.DEBUG && log.isRecording

    fun setEnabled(value: Boolean){log.setEnabled(BuildConfig.DEBUG && value)}
    fun clear()=log.clear()
    fun start(){if(BuildConfig.DEBUG)log.start()}
    fun finish(){if(BuildConfig.DEBUG)log.finish()}
    @PublishedApi internal fun append(event: String){if(BuildConfig.DEBUG)log.record(event)}

    /** Inline keeps the disabled hot path free of both supplier execution and lambda allocation. */
    inline fun record(event: ()->String){if(isRecording)append(event())}

    fun touch(event: MotionEvent) {
        if(!isEnabled)return
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && event.pointerCount>=2)start()
        if(!isRecording)return
        record{"touch action=${event.actionMasked} pointers=${event.pointerCount} x=${event.x} y=${event.y}"}
        // Keep the session through final UP/CANCEL so late geometry remains attributable.
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)finish()
    }

    /** Reading a report never enables recording or starts a delayed operation. */
    fun snapshot(): String {
        if(!BuildConfig.DEBUG)return "Диагностика масштабирования доступна только в debug-сборке."
        val lines=log.snapshot()
        return buildString {
            appendLine("Диагностика HTML-масштабирования")
            appendLine("Версия: "+BuildConfig.VERSION_NAME)
            appendLine("Android: "+android.os.Build.VERSION.SDK_INT)
            appendLine("Устройcтво: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
            appendLine("Запись: "+if(isEnabled)"включена"else"выключена")
            appendLine("Событий: "+lines.size)
            if(lines.isEmpty())appendLine("Жесты масштабирования ещё не зарегистрированы.")
            lines.forEach{appendLine(it)}
        }
    }
}
