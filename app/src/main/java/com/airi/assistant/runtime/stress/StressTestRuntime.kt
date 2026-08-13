package com.airi.assistant.runtime.stress

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * StressTestRuntime — Phase R2 automated stress harness.
 *
 * Drives all AIRI subsystems under simulated peak load to surface:
 *   - ANR risks (main-thread blocking under load)
 *   - Flow overflows (SharedFlow buffer exhaustion)
 *   - Recomposition storms (Compose invalidation cascades)
 *   - Coroutine cancellation safety (cancelled children don't leak)
 *   - Deadlocks (timeouts on concurrent lock acquisition)
 *   - Memory pressure (heap behavior under activity event floods)
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 * Only construct from the Developer Center or test runners — never in
 * production code paths. Each [runSuite] call is destructive: it emits
 * events into shared buses used by the real runtime.
 *
 * Inject lambdas for subsystems the test needs to drive; null = skip that
 * subsystem.
 */
class StressTestRuntime(
    private val emitActivityEvent: (suspend (String) -> Unit)? = null,
    private val sendChatMessage:   (suspend (String) -> Unit)? = null,
    private val triggerAgentTask:  (suspend (String) -> Unit)? = null,
    private val triggerConnector:  (suspend (String) -> Unit)? = null
) {

    private val TAG   = "StressTestRuntime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class StressPhase {
        ACTIVITY_FLOOD,
        STREAMING_RESPONSES,
        MULTI_AGENT_PARALLEL,
        CONNECTOR_BURST,
        RAPID_NAVIGATION,
        MARKDOWN_STRESS,
        COMBINED
    }

    data class StressResult(
        val phase:       StressPhase,
        val durationMs:  Long,
        val errorCount:  Int,
        val timeouts:    Int,
        val passed:      Boolean,
        val notes:       String = ""
    )

    private val _results = MutableStateFlow<List<StressResult>>(emptyList())
    val results: StateFlow<List<StressResult>> = _results.asStateFlow()

    @Volatile var running = false
        private set
    private var suiteJob: Job? = null

    // ── Public API ──────────────────────────────────────────────────────────

    fun runSuite(phases: Set<StressPhase> = StressPhase.values().toSet()) {
        if (running) { Log.w(TAG, "Stress suite already running"); return }
        running = true
        suiteJob = scope.launch {
            val accumulated = mutableListOf<StressResult>()
            for (phase in phases) {
                if (!isActive) break
                Log.i(TAG, "AIRI_RUNTIME STRESS_PHASE_START phase=$phase")
                val result = runPhase(phase)
                accumulated.add(result)
                _results.value = accumulated.toList()
                Log.i(TAG, "AIRI_RUNTIME STRESS_PHASE_DONE phase=$phase " +
                        "passed=${result.passed} errors=${result.errorCount} ms=${result.durationMs}")
                delay(2_000L) // cool-down between phases
            }
            running = false
            Log.i(TAG, "AIRI_RUNTIME STRESS_SUITE_COMPLETE phases=${phases.size} " +
                    "failures=${accumulated.count { !it.passed }}")
        }
    }

    fun cancel() {
        suiteJob?.cancel()
        running = false
    }

    // ── Phase runners ───────────────────────────────────────────────────────

    private suspend fun runPhase(phase: StressPhase): StressResult {
        val t0 = System.currentTimeMillis()
        var errors = 0
        var timeouts = 0
        var notes = ""

        try {
            when (phase) {
                StressPhase.ACTIVITY_FLOOD -> {
                    // Emit 500 activity events back-to-back
                    repeat(500) { i ->
                        runCatching {
                            emitActivityEvent?.invoke("StressEvent_$i")
                        }.onFailure { errors++ }
                        if (i % 100 == 0) delay(10)
                    }
                    notes = "500 events injected"
                }

                StressPhase.STREAMING_RESPONSES -> {
                    // Simulate 20 simultaneous streaming responses
                    val jobs = (1..20).map { idx ->
                        scope.async {
                            runCatching {
                                withTimeout(10_000L) {
                                    sendChatMessage?.invoke("Stream stress test $idx: " +
                                            "a".repeat(500))
                                }
                            }.onFailure { e ->
                                if (e is kotlinx.coroutines.TimeoutCancellationException) timeouts++
                                else errors++
                            }
                        }
                    }
                    jobs.awaitAll()
                    notes = "20 parallel streams"
                }

                StressPhase.MULTI_AGENT_PARALLEL -> {
                    // Fire 10 agents simultaneously
                    val jobs = (1..10).map { idx ->
                        scope.async {
                            runCatching {
                                withTimeout(15_000L) {
                                    triggerAgentTask?.invoke("agent_stress_task_$idx")
                                }
                            }.onFailure { e ->
                                if (e is kotlinx.coroutines.TimeoutCancellationException) timeouts++
                                else errors++
                            }
                        }
                    }
                    jobs.awaitAll()
                    notes = "10 parallel agents"
                }

                StressPhase.CONNECTOR_BURST -> {
                    // Rapid-fire connector calls
                    repeat(50) { i ->
                        runCatching {
                            withTimeout(5_000L) {
                                triggerConnector?.invoke("connector_stress_$i")
                            }
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.TimeoutCancellationException) timeouts++
                            else errors++
                        }
                        delay(50)
                    }
                    notes = "50 connector calls at 50ms intervals"
                }

                StressPhase.RAPID_NAVIGATION -> {
                    // This phase validates that navigation state doesn't corrupt under spam.
                    // Actual navigation is driven from the UI layer; here we just log the intent.
                    Log.i(TAG, "AIRI_RUNTIME STRESS_NAVIGATION_INTENT 30 rapid switches")
                    repeat(30) { i ->
                        emitActivityEvent?.invoke("NAV_SWITCH_$i")
                        delay(100)
                    }
                    notes = "30 navigation switch events simulated"
                }

                StressPhase.MARKDOWN_STRESS -> {
                    // Long markdown message — tests Compose LazyColumn stability
                    val bigMarkdown = buildString {
                        repeat(200) { i ->
                            appendLine("## Section $i")
                            appendLine("Lorem ipsum dolor sit amet, **bold text**, _italic_, `code`.")
                            appendLine("- Item A\n- Item B\n- Item C")
                            appendLine("```kotlin\nval x = $i * 2\n```")
                        }
                    }
                    runCatching {
                        sendChatMessage?.invoke(bigMarkdown)
                    }.onFailure { errors++ }
                    notes = "200-section markdown rendered"
                }

                StressPhase.COMBINED -> {
                    // Shotgun all subsystems simultaneously
                    listOf(
                        scope.async { runPhase(StressPhase.ACTIVITY_FLOOD) },
                        scope.async { runPhase(StressPhase.STREAMING_RESPONSES) },
                        scope.async { runPhase(StressPhase.CONNECTOR_BURST) }
                    ).awaitAll().forEach { r ->
                        errors  += r.errorCount
                        timeouts += r.timeouts
                    }
                    notes = "Combined flood"
                }
            }
        } catch (e: Exception) {
            errors++
            notes = "Phase threw: ${e.message}"
            Log.e(TAG, "AIRI_RUNTIME STRESS_PHASE_ERROR phase=$phase", e)
        }

        val durationMs = System.currentTimeMillis() - t0
        return StressResult(
            phase      = phase,
            durationMs = durationMs,
            errorCount = errors,
            timeouts   = timeouts,
            passed     = errors == 0 && timeouts < 3,
            notes      = notes
        )
    }
}
