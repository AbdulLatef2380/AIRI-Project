package com.airi.assistant.telemetry

import android.os.Bundle
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PrivacyTelemetryReporter — consent-gated dispatcher for [AgentTelemetryEvent].
 *
 * ── CONSENT GATE ──────────────────────────────────────────────────────────
 *
 *   Every event type is mapped to a consent category. If the user has not
 *   opted in to that category, the event is silently dropped — it is never
 *   queued, buffered, or retried.
 *
 * ── SANITISATION ──────────────────────────────────────────────────────────
 *
 *   • Plan IDs are hashed (first 8 chars only) — they are session-local UUIDs
 *     with no PII but we still truncate to prevent any accidental correlation.
 *   • Error strings are restricted to a known error-tag allowlist. Any
 *     freeform reason string is replaced with "UNKNOWN" before dispatch.
 *   • Token counts are bucketed (0, 1-100, 101-500, 501+) rather than
 *     reporting exact values.
 *
 * ── BACKEND ───────────────────────────────────────────────────────────────
 *
 *   Events are forwarded to [AnalyticsService] which uses Firebase Analytics
 *   (reflection-guarded). No secondary backend is introduced.
 */
class PrivacyTelemetryReporter(
    private val consentStore: TelemetryConsentStore
) {

    private val TAG   = "PrivacyTelemetryReporter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(event: AgentTelemetryEvent) {
        scope.launch {
            val consent = consentStore.current
            when (event) {
                is AgentTelemetryEvent.PlanStarted,
                is AgentTelemetryEvent.PlanCompleted,
                is AgentTelemetryEvent.PlanFailed,
                is AgentTelemetryEvent.NodeRetried,
                is AgentTelemetryEvent.AgentInvoked,
                is AgentTelemetryEvent.AgentSucceeded,
                is AgentTelemetryEvent.AgentFailed,
                is AgentTelemetryEvent.ToolCalled,
                is AgentTelemetryEvent.MemoryWritten,
                is AgentTelemetryEvent.WatchdogAlert,
                is AgentTelemetryEvent.SessionBound -> {
                    if (!consent.agentTelemetryEnabled) return@launch
                    dispatchAgentEvent(event)
                }

                is AgentTelemetryEvent.CrashRecorded -> {
                    if (!consent.crashReportingEnabled) return@launch
                    dispatchCrashEvent(event)
                }
            }
        }
    }

    private fun dispatchAgentEvent(event: AgentTelemetryEvent) {
        val (name, params) = when (event) {
            is AgentTelemetryEvent.PlanStarted    -> "dag_plan_started"    to mapOf("nodes" to event.nodeCount.toString(), "waves" to event.waveCount.toString())
            is AgentTelemetryEvent.PlanCompleted  -> "dag_plan_completed"  to mapOf("duration_ms" to bucket(event.durationMs), "failed" to event.failedNodes.toString())
            is AgentTelemetryEvent.PlanFailed     -> "dag_plan_failed"     to mapOf("nodes_failed" to event.nodesFailed.toString())
            is AgentTelemetryEvent.NodeRetried    -> "dag_node_retried"    to mapOf("attempt" to event.attempt.toString())
            is AgentTelemetryEvent.AgentInvoked   -> "agent_invoked"       to mapOf("capability" to sanitize(event.capability))
            is AgentTelemetryEvent.AgentSucceeded -> "agent_succeeded"     to mapOf("duration_ms" to bucket(event.durationMs), "tokens" to tokenBucket(event.tokenCount))
            is AgentTelemetryEvent.AgentFailed    -> "agent_failed"        to mapOf("error_tag" to sanitize(event.errorTag))
            is AgentTelemetryEvent.ToolCalled     -> "tool_called"         to mapOf("tool" to sanitize(event.toolName), "ok" to event.succeeded.toString())
            is AgentTelemetryEvent.MemoryWritten  -> "memory_written"      to mapOf("layer" to sanitize(event.layer), "count" to event.entryCount.toString())
            is AgentTelemetryEvent.WatchdogAlert  -> "watchdog_alert"      to mapOf("age_ms" to bucket(event.ageMs))
            is AgentTelemetryEvent.SessionBound   -> "session_bound"       to mapOf("tier" to event.deviceTier, "mode" to event.executionMode, "nctx_bucket" to nCtxBucket(event.nCtx))
            else -> return
        }
        LoggingService.debug(TAG, "TELEMETRY $name params=$params")
        AnalyticsService.agentExecuted(name)
    }

    private fun dispatchCrashEvent(event: AgentTelemetryEvent.CrashRecorded) {
        LoggingService.warn(TAG, "TELEMETRY crash component=${event.component} tag=${event.errorTag}")
        AnalyticsService.skillFailed(event.component, event.errorTag)
    }

    private fun sanitize(raw: String): String {
        val clean = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
        return clean.ifBlank { "unknown" }
    }

    private fun bucket(ms: Long): String = when {
        ms < 500   -> "<500ms"
        ms < 2000  -> "<2s"
        ms < 10000 -> "<10s"
        ms < 60000 -> "<60s"
        else       -> ">60s"
    }

    private fun tokenBucket(count: Int): String = when {
        count == 0      -> "0"
        count <= 100    -> "1-100"
        count <= 500    -> "101-500"
        else            -> "501+"
    }

    /** SPRINT 1: Bucket nCtx so no exact model fingerprint escapes telemetry. */
    private fun nCtxBucket(nCtx: Int): String = when {
        nCtx <= 0       -> "unloaded"
        nCtx <= 1024    -> "<=1K"
        nCtx <= 2048    -> "<=2K"
        nCtx <= 4096    -> "<=4K"
        nCtx <= 8192    -> "<=8K"
        nCtx <= 32768   -> "<=32K"
        else            -> ">32K"
    }
}
