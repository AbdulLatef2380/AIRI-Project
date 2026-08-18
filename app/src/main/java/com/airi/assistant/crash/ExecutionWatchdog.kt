package com.airi.assistant.crash

import com.airi.assistant.agent.execution.runtime.ExecutionGraphRuntime
import com.airi.assistant.agent.execution.runtime.ExecutionGraphSnapshot
import com.airi.assistant.agent.execution.runtime.PlanExecutionState
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.telemetry.AgentTelemetryEvent
import com.airi.assistant.telemetry.PrivacyTelemetryReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ExecutionWatchdog — periodically scans active plan snapshots and raises
 * alerts when a plan has been stuck in RUNNING state beyond a threshold.
 *
 * ── DETECTION LOGIC ───────────────────────────────────────────────────────
 *
 *   Every [SCAN_INTERVAL_MS] the watchdog fetches all active snapshots from
 *   [ExecutionGraphRuntime] and checks:
 *
 *     age = now - snapshot.startedAtMs
 *
 *   If age > [STUCK_THRESHOLD_MS] and the plan is still RUNNING, the watchdog
 *   records a [WatchdogAlert] event and optionally cancels the plan.
 *
 * ── AUTO-CANCEL ───────────────────────────────────────────────────────────
 *
 *   When [autoCancelStuck] is true (default: false), the watchdog calls
 *   [ExecutionGraphRuntime.cancel] after logging the alert. This is opt-in
 *   because some multi-step plans legitimately take > 5 minutes.
 *
 * ── LIFECYCLE ─────────────────────────────────────────────────────────────
 *
 *   Call [start] after ServiceLocator is ready. The watchdog runs until the
 *   process dies (no explicit stop needed — it uses a SupervisorJob scope).
 */
class ExecutionWatchdog(
    private val runtime:        ExecutionGraphRuntime,
    private val crashReporter:  OrchestratorCrashReporter,
    private val telemetry:      PrivacyTelemetryReporter,
    private val autoCancelStuck: Boolean = false,
    // Optional health monitor — when provided, stuck plans are reflected in
    // the health report so the UI and ConnectorHealthMonitor both see them.
    private val healthMonitor:  RuntimeHealthMonitor? = null
) {

    private val TAG   = "ExecutionWatchdog"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            LoggingService.info(TAG, "AIRI WATCHDOG_STARTED interval=${SCAN_INTERVAL_MS}ms threshold=${STUCK_THRESHOLD_MS}ms")
            while (isActive) {
                delay(SCAN_INTERVAL_MS)
                scan()
            }
        }
    }

    private fun scan() {
        val now = System.currentTimeMillis()
        val snapshots: List<ExecutionGraphSnapshot> = runtime.allActiveSnapshots()

        for (snap in snapshots) {
            // Always keep healthMonitor informed of active plans, not just stuck ones.
            // recordAgentStart is idempotent-safe (ConcurrentHashMap.put with same key).
            healthMonitor?.recordAgentStart(snap.planId)

            if (snap.executionState != PlanExecutionState.RUNNING) {
                // Plan has completed (DONE / FAILED / CANCELLED) — remove from health tracker.
                healthMonitor?.recordAgentEnd(snap.planId)
                continue
            }
            val age = now - snap.startedAtMs
            if (age < STUCK_THRESHOLD_MS) continue

            LoggingService.warn(TAG, "AIRI WATCHDOG_STUCK_DETECTED planId=${snap.planId} ageMs=$age")

            telemetry.report(
                AgentTelemetryEvent.WatchdogAlert(
                    planId = snap.planId,
                    nodeId = snap.pendingNodeIds.firstOrNull() ?: "unknown",
                    ageMs  = age
                )
            )

            crashReporter.reportManual(
                component = "watchdog",
                errorTag  = "PLAN_STUCK",
                message   = "Plan ${snap.planId} stuck in RUNNING for ${age}ms",
                planId    = snap.planId
            )

            if (autoCancelStuck) {
                LoggingService.warn(TAG, "AIRI WATCHDOG_AUTO_CANCEL planId=${snap.planId}")
                runtime.cancel(snap.planId)
                healthMonitor?.recordAgentEnd(snap.planId)
            }
        }
    }

    companion object {
        private const val SCAN_INTERVAL_MS  = 60_000L
        private const val STUCK_THRESHOLD_MS = 5 * 60_000L
    }
}
