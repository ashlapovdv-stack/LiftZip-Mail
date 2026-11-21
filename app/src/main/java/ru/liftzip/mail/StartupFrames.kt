package ru.liftzip.mail

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class StartupFrames {
    private data class Sample(val atMs: Long, val total: Long, val deadline: Long, val layout: Long, val draw: Long)
    private val slow = mutableListOf<Sample>()
    private var frames = 0
    private var late = 0
    private var dropped = 0
    private var firstDraw: Long? = null

    fun record(atMs: Long, total: Long, deadline: Long, layout: Long, draw: Long, first: Boolean, lost: Int) {
        dropped += lost
        if (total < 0) return
        frames++
        if (first) {
            if (firstDraw == null) firstDraw = total
            return
        }
        if (total <= deadline) return
        late++
        slow += Sample(atMs, total, deadline, layout, draw)
        slow.sortByDescending { it.total }
        if (slow.size > 6) slow.removeAt(slow.lastIndex)
    }

    private fun ms(nanos: Long) = if (nanos < 0) "—" else "%.1f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    fun describe(): String = buildString {
        appendLine("Получено кадров: $frames. Опоздало без учёта первого кадра: $late. Пропущено отчётов: $dropped.")
        firstDraw?.let { appendLine("Первый кадр: ${ms(it)} мс.") }
        for (sample in slow.sortedBy { it.atMs }) {
            appendLine("+${sample.atMs} мс: кадр ${ms(sample.total)} мс из ${ms(sample.deadline)} мс; " +
                "разметка ${ms(sample.layout)} мс, отрисовка ${ms(sample.draw)} мс.")
        }
    }.trimEnd()
}

/** Single opt-in recording, ending when the first window is hidden or after eight seconds. */
internal class StartupFrameProbe(private val context: Context) {
    private val key = "record-next-startup"
    private val preferences get() = context.getSharedPreferences("mail-settings", Context.MODE_PRIVATE)
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var firstWindow = true
    private var recording: Recording? = null
    var armed by mutableStateOf(false)
        private set
    var report by mutableStateOf("")
        private set

    private class Recording(val window: Window, val startedNs: Long, val processAgeMs: Long, val fallbackDeadline: Long) {
        val frames = StartupFrames()
        var cachedMs: Long? = null
        var readyMs: Long? = null
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        fun elapsedMs() = (System.nanoTime() - startedNs) / 1_000_000L
    }
    private val stopAfterWindow = Runnable { stop() }

    fun arm() {
        preferences.edit().putBoolean(key, true).apply()
        armed = true
        report = ""
    }

    fun start(window: Window, refreshHz: Float) {
        if (!firstWindow) return
        firstWindow = false
        if (!preferences.getBoolean(key, false)) return
        preferences.edit().remove(key).apply()
        armed = false
        val age = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        val current = Recording(window, System.nanoTime(), age, (1_000_000_000.0 / refreshHz.coerceAtLeast(1f)).toLong())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val at = if (intended > 0) ((intended - current.startedNs) / 1_000_000L).coerceAtLeast(0) else current.elapsedMs()
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
            current.frames.record(at, metrics.getMetric(FrameMetrics.TOTAL_DURATION), deadline.takeIf { it > 0 } ?: current.fallbackDeadline,
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), metrics.getMetric(FrameMetrics.DRAW_DURATION),
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, lost)
        }
        current.listener = listener
        recording = current
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.postDelayed(stopAfterWindow, 8_000L)
    }

    fun markInbox(cached: Boolean, ready: Boolean) {
        val current = recording ?: return
        if (cached && current.cachedMs == null) current.cachedMs = current.elapsedMs()
        if (ready && current.readyMs == null) current.readyMs = current.elapsedMs()
    }

    fun stop(window: Window? = null) {
        val current = recording ?: return
        if (window != null && window !== current.window) return
        recording = null
        handler.removeCallbacks(stopAfterWindow)
        current.window.removeOnFrameMetricsAvailableListener(current.listener)
        report = "Почта ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})\n" +
            "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER}\n" +
            "Запуск списка · возраст процесса ${current.processAgeMs} мс · запись ${current.elapsedMs()} мс\n" +
            "Сохранённая страница: ${current.cachedMs?.let { "+$it мс" } ?: "—"}. " +
            "Полное состояние списка: ${current.readyMs?.let { "+$it мс" } ?: "—"}.\n" +
            current.frames.describe()
    }
}
