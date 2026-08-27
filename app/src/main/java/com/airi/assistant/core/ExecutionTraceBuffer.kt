package com.airi.assistant.core

/**
 * Bounded, execution-owned trace buffer for user-safe progress events.
 *
 * The buffer deliberately stores summaries only; callers must redact any
 * user-controlled or provider-controlled text before appending it.
 */
class ExecutionTraceBuffer(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = ArrayDeque<ExecutionTraceEvent>(maxEntries)
    private var nextSequence = 1L

    @Synchronized
    fun begin(executionId: String): List<ExecutionTraceEvent> {
        require(executionId.isNotBlank()) { "executionId is required" }
        entries.clear()
        nextSequence = 1L
        return snapshot()
    }

    @Synchronized
    fun append(
        executionId: String,
        kind: ExecutionTraceKind,
        summary: String,
        detail: String? = null,
        actionId: String? = null,
        durationMs: Long? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ExecutionTraceEvent? {
        if (executionId.isBlank() || summary.isBlank()) return null
        val event = ExecutionTraceEvent(
            executionId = executionId,
            sequence = nextSequence++,
            timestampMs = timestampMs,
            kind = kind,
            summary = summary,
            detail = detail,
            actionId = actionId?.takeIf { it.isNotBlank() },
            durationMs = durationMs?.coerceAtLeast(0L),
        )
        entries.addLast(event)
        while (entries.size > maxEntries) entries.removeFirst()
        return event
    }

    @Synchronized
    fun snapshot(): List<ExecutionTraceEvent> = entries.toList()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 150
    }
}

data class ExecutionTraceEvent(
    val executionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val kind: ExecutionTraceKind,
    val summary: String,
    val detail: String? = null,
    /** Stable owner for a single tool action when [kind] is a tool lifecycle event. */
    val actionId: String? = null,
    /** Elapsed execution time when a terminal tool event provides it. */
    val durationMs: Long? = null,
)

enum class ExecutionTraceKind {
    PLANNING,
    STEP_STARTED,
    STEP_COMPLETED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED,
    TOOL_CANCELLED,
    RECOVERING,
    REFLECTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
