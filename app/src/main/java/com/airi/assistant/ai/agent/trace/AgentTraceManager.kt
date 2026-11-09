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

    fun startTrace(
        originalInput: String,
        executionId: String = "",
        sessionId: String = "",
        messageId: Long? = null,
    ): String {
        pruneStale()
        val traceId = UUID.randomUUID().toString()
        activeBuilders[traceId] = ActiveTrace(
            base = AgentTrace(
                id = traceId,
                originalInput = PrivacyGuard.redactForTrace(originalInput, INPUT_CHARS),
                executionId = executionId,
                sessionId = sessionId,
                messageId = messageId,
            )
        )
        return traceId
    }

    fun addStep(traceId: String, step: AgentStep) {
        val builder = activeBuilders[traceId] ?: return
        val redacted = redactStep(step)
        synchronized(builder) {
            if (builder.steps.size >= MAX_STEPS) {
                // Keep newest evidence: terminal failures and the final
                // recovery/completion step are more useful than old plan entries.
                builder.steps.removeAt(0)
                builder.dropped++
            }
            builder.steps.add(redacted)
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

    /**
     * Publishes a snapshot of a running (or just-finished) execution. Unlike [recordChatExecution]
     * it never overwrites a final record already tied to a chat message, so a late live update
     * cannot erase the saved result. Snapshots still marked [AgentTrace.inProgress] are kept in
     * memory only; abandoned ones older than [STALE_TRACE_MS] are dropped.
     */
    fun publishLive(trace: AgentTrace) {
        val cutoff = System.currentTimeMillis() - STALE_TRACE_MS
        var applied = false
        _traces.update { current ->
            val existing = current.firstOrNull { it.id == trace.id }
            if (existing != null && !existing.inProgress && existing.messageId != null) {
                applied = false
                return@update current
            }
            applied = true
            val kept = current.filterNot { it.id == trace.id || (it.inProgress && it.timestamp < cutoff) }
            (kept + trace).takeLast(maxTraces)
        }
        if (applied && !trace.inProgress) persistSnapshot()
    }

    /** Drops a live snapshot that never turned into a record; a finished trace with this id is kept. */
    fun removeLiveTrace(traceId: String) {
        _traces.update { current -> current.filterNot { it.id == traceId && it.inProgress } }
    }

    fun getTrace(traceId: String): AgentTrace? =
        _traces.value.find { it.id == traceId }

    /** Removes finished traces owned by one persisted chat message. */
    fun deleteForMessage(messageId: Long) {
        if (messageId <= 0L) return
        activeBuilders.entries.removeIf { it.value.base.messageId == messageId }
        val removed = _traces.value.filter { it.messageId == messageId }
        _traces.update { current -> current.filterNot { it.messageId == messageId } }
        store?.let { target -> persistExecutor.execute { target.deleteMatching { it.messageId == messageId } } }
        notifyRemoved(removed, TraceRemoval.SOME)
    }

    /** Removes one finished trace by id (the single-item delete in the log list). */
    fun deleteTrace(traceId: String) {
        if (traceId.isBlank()) return
        val removed = _traces.value.filter { it.id == traceId }
        _traces.update { current -> current.filterNot { it.id == traceId } }
        store?.let { target -> persistExecutor.execute { target.deleteMatching { it.id == traceId } } }
        notifyRemoved(removed, TraceRemoval.SOME)
    }

    /** Removes finished traces owned by one chat session. */
    fun deleteForSession(sessionId: String) {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return
        activeBuilders.entries.removeIf { it.value.base.sessionId == normalized }
        val removed = _traces.value.filter { it.sessionId == normalized }
        _traces.update { current -> current.filterNot { it.sessionId == normalized } }
        store?.let { target -> persistExecutor.execute { target.deleteMatching { it.sessionId == normalized } } }
        notifyRemoved(removed, TraceRemoval.SOME)
    }

    /**
     * Clears finished traces only. Traces still running keep their builders, so
     * pressing "clear" mid-execution does not silently lose the active one.
     */
    fun clearTraces() {
        val removed = _traces.value
        _traces.value = emptyList()
        store?.let { target -> persistExecutor.execute { target.clear() } }
        notifyRemoved(removed, TraceRemoval.CLEAR_LOGS)
    }

    /**
     * Irreversible local wipe used by account deletion. Unlike [clearTraces],
     * this also abandons active builders so a late callback cannot recreate
     * deleted trace data.
     */
    fun clearAllTraces() {
        activeBuilders.clear()
        val removed = _traces.value
        _traces.value = emptyList()
        store?.let { target -> persistExecutor.execute { target.clear() } }
        notifyRemoved(removed, TraceRemoval.WIPE)
    }

    /**
     * Projection hook: called with every finalized (not in-progress) trace so read models such as
     * the Room run store are fed from this single source instead of a parallel channel.
     */
    @Volatile
    var onTraceFinalized: ((AgentTrace) -> Unit)? = null

    /** What a removal covered, so derived stores know how much of their own data to drop. */
    enum class TraceRemoval {
        /** Specific traces (one run, one message, one session). */
        SOME,
        /** "Clear logs": every trace in the log; a run still in progress keeps its file snapshots. */
        CLEAR_LOGS,
        /** Irreversible local wipe (account deletion): nothing derived may survive. */
        WIPE,
    }

    /**
     * Projection hook for removals: receives the traces a delete or clear just dropped, so the Room
     * run store and the file snapshots, which are derived from this store, are cleaned from the same
     * source instead of keeping copies of deleted runs.
     */
    @Volatile
    var onTracesRemoved: ((removed: List<AgentTrace>, kind: TraceRemoval) -> Unit)? = null

    private fun notifyRemoved(removed: List<AgentTrace>, kind: TraceRemoval) {
        if (removed.isEmpty() && kind == TraceRemoval.SOME) return
        runCatching { onTracesRemoved?.invoke(removed, kind) }
    }

    private fun publish(trace: AgentTrace) {
        _traces.update { current ->
            (current.filterNot { it.id == trace.id } + trace).takeLast(maxTraces)
        }
        if (!trace.inProgress) runCatching { onTraceFinalized?.invoke(trace) }
        persistSnapshot()
    }

    private fun persistSnapshot() {
        val target = store ?: return
        // Read the flow when the write runs, not now: a snapshot captured earlier
        // could predate the startup merge in attachStore and erase saved traces.
        // Running snapshots are never persisted.
        persistExecutor.execute { target.save(_traces.value.filterNot { it.inProgress }) }
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
