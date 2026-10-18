package com.airi.assistant.core

import java.util.Locale

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
    private var activeExecutionId = ""
    private var activeSessionId = ""

    @Synchronized
    fun begin(executionId: String, sessionId: String = ""): List<ExecutionTraceEvent> {
        require(executionId.isNotBlank()) { "executionId is required" }
        entries.clear()
        nextSequence = 1L
        activeExecutionId = executionId
        activeSessionId = sessionId
        return snapshot()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        nextSequence = 1L
        activeExecutionId = ""
        activeSessionId = ""
    }

    @Synchronized
    fun append(
        executionId: String,
        kind: ExecutionTraceKind,
        summary: String,
        detail: String? = null,
        actionId: String? = null,
        durationMs: Long? = null,
        errorCode: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ExecutionTraceEvent? {
        if (executionId.isBlank() || executionId != activeExecutionId || summary.isBlank()) return null
        val event = ExecutionTraceEvent(
            executionId = executionId,
            sequence = nextSequence++,
            timestampMs = timestampMs,
            kind = kind,
            summary = summary,
            detail = detail,
            actionId = actionId?.takeIf { it.isNotBlank() },
            durationMs = durationMs?.coerceAtLeast(0L),
            errorCode = normalizeErrorCode(errorCode),
            sessionId = activeSessionId,
        )
        entries.addLast(event)
        while (entries.size > maxEntries) entries.removeFirst()
        return event
    }

    @Synchronized
    fun snapshot(): List<ExecutionTraceEvent> = entries.toList()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 150

        private val ERROR_CODE_PATTERN = Regex("[a-z0-9][a-z0-9_.:-]{0,63}")

        internal fun normalizeErrorCode(value: String?): String? = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { ERROR_CODE_PATTERN.matches(it) }
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
    /** Stable machine-readable tool failure code, when the event is a failure. */
    val errorCode: String? = null,
    /** Owning conversation session; blank for executions not attached to a chat. */
    val sessionId: String = "",
    /** Stable semantic label used by the UI localization layer. */
    val machineTag: String? = null,
) {
    /** Stable correlation key for this ordered event within one execution. */
    val eventId: String get() = "$executionId:event:$sequence"
    /** Stable terminal delivery key; non-terminal events still have a deterministic value. */
    val deliveryId: String get() = "$executionId:delivery:$sequence"
}

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
