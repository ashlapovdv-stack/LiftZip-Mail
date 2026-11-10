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
    private var active = false
    private var dropped = 0
    private var generation = 0
    private val flushTask = Runnable { flush() }

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
        while (records.isNotEmpty()) Log.i(TAG, records.removeFirst())
        dropped = 0
    }
}
