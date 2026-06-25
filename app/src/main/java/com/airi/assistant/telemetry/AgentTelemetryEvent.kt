package com.airi.assistant.telemetry

/**
 * AgentTelemetryEvent — sealed hierarchy of typed telemetry events emitted
 * by the agent execution layer.
 *
 * Events are only dispatched when the user has opted in via
 * [PrivacyTelemetryReporter]. All payloads are sanitised (no PII, no raw
 * user text) before reaching the reporter.
 */
sealed class AgentTelemetryEvent {

    abstract val timestampMs: Long

    // ── Execution Graph ────────────────────────────────────────────────────

    data class PlanStarted(
        val planId:      String,
        val nodeCount:   Int,
        val waveCount:   Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class PlanCompleted(
        val planId:      String,
        val durationMs:  Long,
        val nodeCount:   Int,
        val failedNodes: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class PlanFailed(
        val planId:      String,
        val reason:      String,
        val nodesFailed: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class NodeRetried(
        val planId:      String,
        val nodeId:      String,
        val attempt:     Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    // ── Sub-Agent Execution ────────────────────────────────────────────────

    data class AgentInvoked(
        val agentId:    String,
        val capability: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class AgentSucceeded(
        val agentId:   String,
        val durationMs: Long,
        val tokenCount: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class AgentFailed(
        val agentId:  String,
        val errorTag: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    // ── Tool Calls ─────────────────────────────────────────────────────────

    data class ToolCalled(
        val toolName:  String,
        val succeeded: Boolean,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    // ── Memory ─────────────────────────────────────────────────────────────

    data class MemoryWritten(
        val layer:         String,
        val entryCount:    Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    // ── Session ────────────────────────────────────────────────────────────

    data class SessionBound(
        val deviceTier:    String,
        val executionMode: String,
        /** SPRINT 1: live nCtx from LlamaNative.getNCtx() at session open time. */
        val nCtx:          Int  = 0,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class WatchdogAlert(
        val planId:    String,
        val nodeId:    String,
        val ageMs:     Long,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()

    data class CrashRecorded(
        val component: String,
        val errorTag:  String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentTelemetryEvent()
}
