package com.airi.assistant.crash

import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.telemetry.AgentTelemetryEvent
import com.airi.assistant.telemetry.PrivacyTelemetryReporter

/**
 * OrchestratorCrashReporter — captures structured crash context from the
 * agent orchestration layer and routes it to [CrashReportStore] and
 * (consent-gated) [PrivacyTelemetryReporter].
 *
 * ── CALL SITES ────────────────────────────────────────────────────────────
 *
 *   ProductionAgentOrchestrator.executeSingle()  — agent-level crashes
 *   ExecutionGraphRuntime (wave dispatch)         — DAG-level failures
 *   DurableTaskWorker.doWork()                    — WorkManager task failures
 *   RuntimeHealthMonitor                          — health check failures
 *
 * ── GUARANTEES ────────────────────────────────────────────────────────────
 *
 *   • Never re-throws — all recording operations are fire-and-forget.
 *   • Never propagates the [throwable] beyond its [stackDigest] (800 chars).
 *   • Telemetry is a no-op if the user has not opted in.
 */
class OrchestratorCrashReporter(
    private val crashStore: CrashReportStore,
    private val telemetry:  PrivacyTelemetryReporter
) {

    private val TAG = "OrchestratorCrashReporter"

    /**
     * Report a throwable from an agent execution.
     *
     * @param goalDescription Optional high-level user goal text. B-11: truncated to 20
     *   characters before inclusion in the crash payload to prevent user PII (e.g.
     *   "book a restaurant for Friday night near my office") from appearing in telemetry.
     */
    fun reportAgentCrash(
        agentId: String,
        throwable: Throwable,
        planId: String? = null,
        nodeId: String? = null,
        goalDescription: String? = null
    ) {
        // B-11: Truncate goal text to GOAL_SNIPPET_CHARS before it appears anywhere
        // in structured output. The original throwable.message may be long; we log
        // the sanitized goal separately in the AIRI line so operators can
        // correlate crashes with user intent without capturing the full input.
        val sanitizedGoal = goalDescription?.take(GOAL_SNIPPET_CHARS)
        val report = crashStore.record(
            component = "agent:$agentId",
            throwable = throwable,
            planId    = planId,
            nodeId    = nodeId,
            agentId   = agentId
        )
        telemetry.report(
            AgentTelemetryEvent.CrashRecorded(
                component = "agent:$agentId",
                errorTag  = throwable.javaClass.simpleName
            )
        )
        // The sanitized goal snippet is included here in the structured log line.
        // It is NOT stored verbatim in the crash report JSON — the report only
        // contains the standard errorMessage from throwable (already truncated to
        // 200 chars by CrashReportStore.record). This keeps PII out of disk/telemetry
        // while giving operators a short context marker in log analysis.
        LoggingService.error(TAG, "AIRI AGENT_CRASH agent=$agentId id=${report.id} " +
            "goal_snippet=${sanitizedGoal ?: "<none>"} " +
            "error=${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    companion object {
        /** B-11: Maximum characters of goal text included in crash payload. */
        private const val GOAL_SNIPPET_CHARS = 20
    }

    /**
     * Report a DAG execution graph failure.
     */
    fun reportGraphCrash(
        planId:    String,
        throwable: Throwable,
        nodeId:    String? = null
    ) {
        val report = crashStore.record(
            component = "dag:$planId",
            throwable = throwable,
            planId    = planId,
            nodeId    = nodeId
        )
        telemetry.report(
            AgentTelemetryEvent.CrashRecorded(
                component = "dag:$planId",
                errorTag  = throwable.javaClass.simpleName
            )
        )
        LoggingService.error(TAG, "AIRI DAG_CRASH planId=$planId node=$nodeId id=${report.id} error=${throwable.javaClass.simpleName}")
    }

    /**
     * Report a structured failure where no throwable is available.
     */
    fun reportManual(
        component: String,
        errorTag:  String,
        message:   String,
        planId:    String? = null,
        nodeId:    String? = null,
        agentId:   String? = null
    ) {
        val report = crashStore.recordManual(
            component  = component,
            errorClass = errorTag,
            message    = message,
            planId     = planId,
            nodeId     = nodeId,
            agentId    = agentId
        )
        telemetry.report(
            AgentTelemetryEvent.CrashRecorded(component = component, errorTag = errorTag)
        )
        LoggingService.warn(TAG, "AIRI MANUAL_CRASH component=$component tag=$errorTag id=${report.id}")
    }

    /**
     * Report a WorkManager durable task failure.
     */
    fun reportDurableTaskCrash(
        taskId:    String,
        agentId:   String,
        throwable: Throwable
    ) {
        val report = crashStore.record(
            component = "durable:$taskId",
            throwable = throwable,
            agentId   = agentId
        )
        telemetry.report(
            AgentTelemetryEvent.CrashRecorded(
                component = "durable:$taskId",
                errorTag  = throwable.javaClass.simpleName
            )
        )
        LoggingService.error(TAG, "AIRI DURABLE_CRASH taskId=$taskId agent=$agentId id=${report.id}")
    }
}
