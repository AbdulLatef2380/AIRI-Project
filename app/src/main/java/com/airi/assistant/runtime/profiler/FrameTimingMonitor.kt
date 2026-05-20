package com.airi.assistant.runtime.profiler

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * FrameTimingMonitor — detects UI thread violations and excessive recomposition.
 *
 * Uses a Choreographer-style heartbeat posted to the main thread every
 * [FRAME_BUDGET_MS]. If a heartbeat is delayed beyond [JANK_THRESHOLD_MS]
 * it is classified as a jank frame and logged.
 *
 * Integrate with Compose by calling [recordRecomposition] from a
 * SideEffect{} or remember{} lambda instrumented in heavy screens.
 *
 * ── ANR Detection ────────────────────────────────────────────────────────
 * Posts a sentinel to main handler. If it doesn't execute within
 * [ANR_THRESHOLD_MS] a warning is emitted (does NOT kill the process —
 * this is a diagnostic, not a watchdog).
 */
object FrameTimingMonitor {

    private const val TAG               = "FrameTimingMonitor"
    private const val FRAME_BUDGET_MS   = 16L     // 60fps budget
    private const val JANK_THRESHOLD_MS = 32L     // 2 dropped frames = jank
    private const val ANR_THRESHOLD_MS  = 4_000L  // near-ANR warning
    private const val RECOMP_WARN_RATE  = 10      // warn if >10 recompositions/sec per key

    data class FrameReport(
        val totalFrames:       Long,
        val jankFrames:        Long,
        val jankRatePct:       Float,
        val maxFrameDurationMs:Long,
        val recompositionStats:Map<String, Int>,
        val uiViolationCount:  Int
    )

    private val mainHandler      = Handler(Looper.getMainLooper())
    private val totalFrames      = AtomicLong(0)
    private val jankFrames       = AtomicLong(0)
    private val maxDuration      = AtomicLong(0)
    private val uiViolations     = AtomicInteger(0)
    private val recompCounts     = HashMap<String, AtomicInteger>()

    private val _report = MutableStateFlow(
        FrameReport(0, 0, 0f, 0, emptyMap(), 0)
    )
    val report: StateFlow<FrameReport> = _report.asStateFlow()

    @Volatile private var running = false
    @Volatile private var lastHeartbeatMs = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        lastHeartbeatMs = SystemClock.elapsedRealtime()
        scheduleHeartbeat()
        Log.i(TAG, "AIRI_PROOF FRAME_MONITOR_STARTED")
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "AIRI_PROOF FRAME_MONITOR_STOPPED")
    }

    // ── Compose integration ────────────────────────────────────────────────

    /**
     * Call from a Compose SideEffect or key derivation to track recomposition
     * rate. Key should identify the composable (e.g. "ChatBubble", "ActivityFeed").
     */
    fun recordRecomposition(key: String) {
        val counter = recompCounts.getOrPut(key) { AtomicInteger(0) }
        val count = counter.incrementAndGet()
        if (count % RECOMP_WARN_RATE == 0) {
            Log.w(TAG, "AIRI_PROOF HIGH_RECOMPOSITION key=$key count=$count")
        }
    }

    // ── UI thread violation check ──────────────────────────────────────────

    /**
     * Call from background threads to assert they are NOT on main thread.
     * Records a violation if invoked from main.
     */
    fun assertNotMainThread(context: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiViolations.incrementAndGet()
            Log.e(TAG, "AIRI_PROOF UI_THREAD_VIOLATION context=$context")
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun scheduleHeartbeat() {
        if (!running) return
        val postTime = SystemClock.elapsedRealtime()
        mainHandler.post {
            val executeTime = SystemClock.elapsedRealtime()
            val delay = executeTime - postTime
            totalFrames.incrementAndGet()
            if (delay > maxDuration.get()) maxDuration.set(delay)
            if (delay > JANK_THRESHOLD_MS) {
                jankFrames.incrementAndGet()
                if (delay > ANR_THRESHOLD_MS) {
                    Log.e(TAG, "AIRI_PROOF NEAR_ANR delayMs=$delay")
                } else {
                    Log.w(TAG, "AIRI_PROOF JANK_FRAME delayMs=$delay")
                }
            }
            lastHeartbeatMs = executeTime
            updateReport()
            if (running) mainHandler.postDelayed({ scheduleHeartbeat() }, FRAME_BUDGET_MS)
        }
    }

    private fun updateReport() {
        val total = totalFrames.get()
        val jank  = jankFrames.get()
        _report.value = FrameReport(
            totalFrames        = total,
            jankFrames         = jank,
            jankRatePct        = if (total == 0L) 0f else jank.toFloat() / total * 100f,
            maxFrameDurationMs = maxDuration.get(),
            recompositionStats = recompCounts.mapValues { it.value.get() },
            uiViolationCount   = uiViolations.get()
        )
    }
}
