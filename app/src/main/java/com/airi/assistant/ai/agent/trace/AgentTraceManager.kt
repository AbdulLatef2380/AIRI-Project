package com.airi.assistant.ai.agent.trace

import com.airi.assistant.execution.privacy.PrivacyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Store behind the agent-log screen.
 *
 * The production chat path writes mapped executions through [recordChatExecution].
 * The imperative builder API remains available to producers that emit steps over time.
 */
class AgentTraceManager internal constructor() {

    companion object {
        const val DEFAULT_MAX_TRACES = 50
        /** The agent loop can run up to 16 steps, each with tool events. */
        const val MAX_STEPS = 48
        /** A trace never finalized within this window is treated as abandoned. */
        const val STALE_TRACE_MS = 10 * 60 * 1000L
        private const val MAX_ACTIVE_TRACES = 64

        // PrivacyGuard.redactForTrace clamps to 1_000 characters.
        const val INPUT_CHARS = 1_000
        const val STEP_OUTPUT_CHARS = 500
        const val RESULT_CHARS = 600

        val instance: AgentTraceManager by lazy { AgentTraceManager() }
    }

    private val _traces = MutableStateFlow<List<AgentTrace>>(emptyList())
    val traces: StateFlow<List<AgentTrace>> = _traces.asStateFlow()

    /** Finished traces retained; set through TraceRetention. */
    @Volatile var maxTraces: Int = DEFAULT_MAX_TRACES

    val latestTrace: AgentTrace? get() = _traces.value.lastOrNull()

    private val activeBuilders = ConcurrentHashMap<String, ActiveTrace>()

    /** Serializes snapshot writes so older snapshots never overwrite newer ones. */
    private val persistExecutor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "agent-trace-store").apply { isDaemon = true } } }
    @Volatile private var store: AgentTraceStore? = null

    private class ActiveTrace(
        val base: AgentTrace,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        // Guarded by `this`: addStep may be called from several dispatchers.
        val steps: MutableList<AgentStep> = mutableListOf()
        var dropped: Int = 0
    }

    /** Attaches durable storage and merges previously saved traces. */
    fun attachStore(newStore: AgentTraceStore) {
        store = newStore
        persistExecutor.execute {
            val saved = newStore.load()
            if (saved.isNotEmpty()) {
                _traces.update { current -> mergeTraces(saved, current) }
            }
        }
    }

    fun startTrace(originalInput: String): String {
        pruneStale()
        val traceId = UUID.randomUUID().toString()
        activeBuilders[traceId] = ActiveTrace(
            base = AgentTrace(
                id = traceId,
                originalInput = PrivacyGuard.redactForTrace(originalInput, INPUT_CHARS),
            )
        )
        return traceId
    }

    fun addStep(traceId: String, step: AgentStep) {
        val builder = activeBuilders[traceId] ?: return
        val redacted = redactStep(step)
        synchronized(builder) {
            if (builder.steps.size < MAX_STEPS) builder.steps.add(redacted) else builder.dropped++
        }
    }

    fun finalizeTrace(
        traceId: String,
        finalResult: String,
        success: Boolean,
        executionId: String = "",
        sessionId: String = "",
    ) {
        val builder = activeBuilders.remove(traceId) ?: return
        val (steps, dropped) = synchronized(builder) { builder.steps.toList() to builder.dropped }
        publish(
            builder.base.copy(
                steps = steps,
                finalResult = PrivacyGuard.redactForTrace(finalResult, RESULT_CHARS),
                success = success,
                executionId = executionId,
                sessionId = sessionId,
                truncated = dropped > 0,
                droppedSteps = dropped,
            )
        )
    }

    /** Ends an in-flight trace that will never be finalized normally (cancel, exception). */
    fun cancelTrace(traceId: String, reason: String = "Cancelled") {
        finalizeTrace(traceId, reason, success = false)
    }

    /**
     * Records a finished chat execution built by [ChatExecutionTraceMapper].
     * A trace with the same id replaces the earlier one, so re-recording is safe.
     */
    fun recordChatExecution(trace: AgentTrace) {
        publish(trace)
    }

    fun getTrace(traceId: String): AgentTrace? =
        _traces.value.find { it.id == traceId }

    /**
     * Clears finished traces only. Traces still running keep their builders, so
     * pressing "clear" mid-execution does not silently lose the active one.
     */
    fun clearTraces() {
        _traces.value = emptyList()
        store?.let { target -> persistExecutor.execute { target.clear() } }
    }

    private fun publish(trace: AgentTrace) {
        _traces.update { current ->
            (current.filterNot { it.id == trace.id } + trace).takeLast(maxTraces)
        }
        val target = store ?: return
        // Read the flow when the write runs, not now: a snapshot captured earlier
        // could predate the startup merge in attachStore and erase saved traces.
        persistExecutor.execute { target.save(_traces.value) }
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - STALE_TRACE_MS
        activeBuilders.entries.removeAll { it.value.startedAt < cutoff }
        if (activeBuilders.size >= MAX_ACTIVE_TRACES) {
            activeBuilders.entries
                .sortedBy { it.value.startedAt }
                .take(activeBuilders.size - MAX_ACTIVE_TRACES + 1)
                .forEach { activeBuilders.remove(it.key) }
        }
    }

    private fun redactStep(step: AgentStep): AgentStep = step.copy(
        inputParams = step.inputParams.mapValues { (key, value) ->
            PrivacyGuard.redactTraceField(key, value)
        },
        outputSummary = PrivacyGuard.redactForTrace(step.outputSummary, STEP_OUTPUT_CHARS),
        error = step.error?.let { PrivacyGuard.redactForTrace(it, STEP_OUTPUT_CHARS) },
    )

    private fun mergeTraces(saved: List<AgentTrace>, live: List<AgentTrace>): List<AgentTrace> {
        val liveIds = live.map { it.id }.toSet()
        return (saved.filterNot { it.id in liveIds } + live)
            .sortedBy { it.timestamp }
            .takeLast(maxTraces)
    }
}
