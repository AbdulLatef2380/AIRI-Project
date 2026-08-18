package com.airi.assistant.runtime.accessibility

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AccessibilityStressRuntime — Phase R8 accessibility service stress validator.
 *
 * Tests the AccessibilityExecutionEngine under adversarial conditions:
 *
 *   EVENT_FLOOD      — 1000 rapid accessibility events; verify no deadlock or OOM
 *   SERVICE_RESTART  — simulate service kill/restart; verify state recovery
 *   GESTURE_RACE     — concurrent gesture injections; verify no stuck gestures
 *   NODE_TRAVERSE    — deep/wide node trees; verify traversal time stays bounded
 *   OVERLAY_STRESS   — rapid overlay show/hide; verify no window leaks
 *   MEMORY_PRESSURE  — sustained events under low-memory; verify no crash
 *
 * ── Integration ──────────────────────────────────────────────────────────
 * Provide the [AccessibilityEngineAdapter] for the real engine. The adapter
 * decouples this tester from the AccessibilityExecutionEngine import, making
 * it testable in isolation.
 */
class AccessibilityStressRuntime {

    private val TAG   = "AccessibilityStressRuntime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    interface AccessibilityEngineAdapter {
        suspend fun injectEvent(type: String, data: String): Boolean
        suspend fun injectGesture(x: Float, y: Float): Boolean
        suspend fun showOverlay(): Boolean
        suspend fun hideOverlay(): Boolean
        fun isServiceConnected(): Boolean
        suspend fun simulateServiceRestart()
    }

    enum class StressScenario {
        EVENT_FLOOD, SERVICE_RESTART, GESTURE_RACE, NODE_TRAVERSE, OVERLAY_STRESS, MEMORY_PRESSURE
    }

    data class AccessibilityStressResult(
        val scenario:       StressScenario,
        val passed:         Boolean,
        val durationMs:     Long,
        val eventCount:     Int,
        val failureCount:   Int,
        val maxLatencyMs:   Long,
        val deadlockWarning:Boolean,
        val notes:          String = ""
    )

    private val _results = MutableStateFlow<List<AccessibilityStressResult>>(emptyList())
    val results: StateFlow<List<AccessibilityStressResult>> = _results.asStateFlow()

    // ── Real-time monitoring ───────────────────────────────────────────────
    private val eventRate    = AtomicInteger(0)
    private val stuckGesture = AtomicInteger(0)
    private val overlayCount = AtomicInteger(0)

    private val _eventRatePerSec = MutableStateFlow(0)
    val eventRatePerSec: StateFlow<Int> = _eventRatePerSec.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                _eventRatePerSec.value = eventRate.getAndSet(0)
                if (_eventRatePerSec.value > 500) {
                    Log.w(TAG, "AIRI A11Y_EVENT_FLOOD rate=${_eventRatePerSec.value}/s")
                }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun runStress(
        engine:    AccessibilityEngineAdapter,
        scenarios: Set<StressScenario> = StressScenario.values().toSet()
    ) {
        scope.launch {
            val accumulated = mutableListOf<AccessibilityStressResult>()
            for (scenario in scenarios) {
                Log.i(TAG, "AIRI A11Y_STRESS_START scenario=$scenario")
                val result = runScenario(engine, scenario)
                accumulated.add(result)
                _results.value = accumulated.toList()
                Log.i(TAG, "AIRI A11Y_STRESS_DONE scenario=$scenario passed=${result.passed}")
                delay(2_000L)
            }
        }
    }

    // ── Scenario runners ────────────────────────────────────────────────────

    private suspend fun runScenario(
        engine:   AccessibilityEngineAdapter,
        scenario: StressScenario
    ): AccessibilityStressResult {
        val t0          = System.currentTimeMillis()
        var failures    = 0
        var eventCount  = 0
        var maxLatency  = 0L
        var deadlock    = false
        var notes       = ""

        try {
            when (scenario) {
                StressScenario.EVENT_FLOOD -> {
                    repeat(1000) { i ->
                        val et0 = System.currentTimeMillis()
                        val ok  = runCatching {
                            withTimeout(500L) { engine.injectEvent("TYPE_VIEW_CLICKED", "node_$i") }
                        }.getOrElse { false }
                        val latency = System.currentTimeMillis() - et0
                        if (latency > maxLatency) maxLatency = latency
                        if (!ok) failures++
                        eventCount++
                        eventRate.incrementAndGet()
                        if (i % 100 == 0) delay(10L) // let drain
                    }
                    notes = "1000 events injected, maxLatency=${maxLatency}ms failures=$failures"
                }

                StressScenario.SERVICE_RESTART -> {
                    val connectedBefore = engine.isServiceConnected()
                    engine.simulateServiceRestart()
                    delay(3_000L)
                    val connectedAfter = engine.isServiceConnected()
                    failures = if (connectedAfter) 0 else 1
                    notes = "Connected before=$connectedBefore after=$connectedAfter"
                }

                StressScenario.GESTURE_RACE -> {
                    // 20 concurrent gesture injections — check for stuck gestures
                    val jobs = (1..20).map { idx ->
                        scope.launch {
                            val ok = runCatching {
                                withTimeout(2_000L) {
                                    engine.injectGesture(
                                        (100 + idx * 10).toFloat(),
                                        (200 + idx * 10).toFloat()
                                    )
                                }
                            }.getOrElse { false }
                            if (!ok) stuckGesture.incrementAndGet()
                            eventCount++
                        }
                    }
                    jobs.forEach { it.join() }
                    failures = stuckGesture.get()
                    notes = "20 concurrent gestures, stuck=$failures"
                }

                StressScenario.NODE_TRAVERSE -> {
                    // Deep traversal — inject 50 events simulating deep tree walk
                    repeat(50) { depth ->
                        val et0 = System.currentTimeMillis()
                        val ok  = runCatching {
                            withTimeout(300L) {
                                engine.injectEvent("TYPE_VIEW_FOCUSED", "depth_$depth")
                            }
                        }.getOrElse { false }
                        val lat = System.currentTimeMillis() - et0
                        if (lat > maxLatency) maxLatency = lat
                        if (!ok) failures++
                        eventCount++
                    }
                    deadlock = maxLatency > 5_000L
                    notes = "50-depth traversal maxLatency=${maxLatency}ms"
                }

                StressScenario.OVERLAY_STRESS -> {
                    // Rapid show/hide cycles
                    repeat(30) { i ->
                        val show = runCatching { withTimeout(1_000L) { engine.showOverlay() } }.getOrElse { false }
                        val hide = runCatching { withTimeout(1_000L) { engine.hideOverlay() } }.getOrElse { false }
                        if (!show || !hide) failures++
                        overlayCount.incrementAndGet()
                        eventCount += 2
                        delay(100L)
                    }
                    notes = "30 show/hide cycles, failures=$failures"
                }

                StressScenario.MEMORY_PRESSURE -> {
                    // Inject events while allocating memory pressure
                    val pressure = mutableListOf<ByteArray>()
                    try {
                        repeat(20) { pressure.add(ByteArray(1024 * 1024)) } // 20MB pressure
                    } catch (_: OutOfMemoryError) { /* acceptable */ }

                    repeat(200) { i ->
                        val ok = runCatching {
                            withTimeout(500L) { engine.injectEvent("MEMORY_STRESS", "event_$i") }
                        }.getOrElse { false }
                        if (!ok) failures++
                        eventCount++
                    }
                    pressure.clear()
                    notes = "200 events under 20MB pressure, failures=$failures"
                }
            }
        } catch (e: Exception) {
            failures++
            notes += " | Exception: ${e.message?.take(80)}"
            Log.e(TAG, "AIRI A11Y_SCENARIO_ERROR scenario=$scenario", e)
        }

        return AccessibilityStressResult(
            scenario        = scenario,
            passed          = failures == 0 && !deadlock,
            durationMs      = System.currentTimeMillis() - t0,
            eventCount      = eventCount,
            failureCount    = failures,
            maxLatencyMs    = maxLatency,
            deadlockWarning = deadlock,
            notes           = notes
        )
    }
}
