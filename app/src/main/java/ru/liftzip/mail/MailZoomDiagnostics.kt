package ru.liftzip.mail

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import java.util.ArrayDeque

/**
 * Debug-only, geometry-only trace. No email text, account identifiers or URLs.
 * Events are buffered during the gesture and emitted after the fingers are lifted.
 */
internal object MailZoomDiagnostics {
    private const val TAG = "MailZoomDiag"
    private const val LIMIT = 4096
    private val handler = Handler(Looper.getMainLooper())
    private val records = ArrayDeque<String>()
    private val history = ArrayDeque<String>()
    private const val HISTORY_LIMIT = 12000
    private var active = false
    private var dropped = 0
    private var generation = 0
    private val flushTask = Runnable { flush() }

    /** Retained in memory across reader navigation; never includes message content. */
    fun snapshot(): String {
        if (!BuildConfig.DEBUG) return "Диагностика масштабирования доступна только в debug-сборке."
        val lines = history.toMutableList()
        if (active) lines.addAll(records)
        return buildString {
            appendLine("Диагностика HTML-масштабирования")
            appendLine("Версия: "+BuildConfig.VERSION_NAME)
            appendLine("Android: "+android.os.Build.VERSION.SDK_INT)
            appendLine("Устройcтво: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
            appendLine("Событий: "+lines.size)
            if (lines.isEmpty()) appendLine("Жесты масштабирования ещё не зарегистрированы.")
            lines.forEach { appendLine(it) }
        }
    }

    fun clear() { history.clear(); records.clear(); active = false; handler.removeCallbacks(flushTask) }

    fun start() {
        if (!BuildConfig.DEBUG) return
        if (active) return
        active = true
        generation++
        dropped = 0
        records.clear()
        record("gestureBegin")
    }

    fun record(event: String) {
        if (!BuildConfig.DEBUG || !active) return
        if (records.size >= LIMIT) { records.removeFirst(); dropped++ }
        records.addLast("t=${SystemClock.uptimeMillis()} g=$generation $event")
    }

    fun touch(event: MotionEvent) {
        if (!BuildConfig.DEBUG) return
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) start()
        if (!active) return
        record("touch action=${event.actionMasked} pointers=${event.pointerCount} x=${event.x} y=${event.y}")
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount <= 2)) finish()
    }

    fun finish() {
        if (!active) return
        handler.removeCallbacks(flushTask)
        handler.postDelayed(flushTask, 900L)
    }

    private fun flush() {
        if (!active) return
        record("gestureEnd dropped=$dropped")
        active = false
        while (records.isNotEmpty()) {
            val line = records.removeFirst()
            if (history.size >= HISTORY_LIMIT) history.removeFirst()
            history.addLast(line)
            Log.i(TAG, line)
        }
        dropped = 0
    }
}
