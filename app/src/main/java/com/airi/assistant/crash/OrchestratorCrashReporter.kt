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
     */
    fun reportAgentCrash(
        agentId: String,
        throwable: Throwable,
        planId: String? = null,
        nodeId: String? = null
    ) {
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
        LoggingService.error(TAG, "AIRI_PROOF AGENT_CRASH agent=$agentId id=${report.id} error=${throwable.javaClass.simpleName}: ${throwable.message}")
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
        LoggingService.error(TAG, "AIRI_PROOF DAG_CRASH planId=$planId node=$nodeId id=${report.id} error=${throwable.javaClass.simpleName}")
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
        LoggingService.warn(TAG, "AIRI_PROOF MANUAL_CRASH component=$component tag=$errorTag id=${report.id}")
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
        LoggingService.error(TAG, "AIRI_PROOF DURABLE_CRASH taskId=$taskId agent=$agentId id=${report.id}")
    }
}
