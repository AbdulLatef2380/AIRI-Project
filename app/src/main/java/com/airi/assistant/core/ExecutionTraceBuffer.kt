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
        machineTag: String? = null,
        parentId: String? = null,
        inputParams: Map<String, String> = emptyMap(),
        timestampMs: Long = System.currentTimeMillis(),
    ): ExecutionTraceEvent? {
        if (executionId.isBlank() || executionId != activeExecutionId || summary.isBlank()) return null
        val requiresActionIdentity = kind in setOf(
            ExecutionTraceKind.TOOL_STARTED,
            ExecutionTraceKind.TOOL_COMPLETED,
            ExecutionTraceKind.TOOL_FAILED,
            ExecutionTraceKind.TOOL_CANCELLED,
        )
        if (requiresActionIdentity && actionId.isNullOrBlank()) return null
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
            machineTag = machineTag?.takeIf { it.isNotBlank() },
            parentId = parentId?.takeIf { it.isNotBlank() },
            inputParams = inputParams,
        )
        entries.addLast(event)
        while (entries.size > maxEntries) {
            // Keep the beginning plus the evidence that explains the outcome.
            // In particular, recovery events must not disappear before the
            // terminal result when a tool-heavy run exceeds the ring limit.
            val evictable = entries.indexOfFirst { !it.kind.isOutcomeEvidence() }
            if (evictable >= 0) {
                entries.removeAt(evictable)
            } else {
                entries.removeFirst()
            }
        }
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

private fun ExecutionTraceKind.isOutcomeEvidence(): Boolean = this in setOf(
    ExecutionTraceKind.TOOL_COMPLETED,
    ExecutionTraceKind.TOOL_FAILED,
    ExecutionTraceKind.TOOL_CANCELLED,
    ExecutionTraceKind.RECOVERING,
    ExecutionTraceKind.COMPLETED,
    ExecutionTraceKind.FAILED,
    ExecutionTraceKind.CANCELLED,
    ExecutionTraceKind.APPROVAL_CANCELLED,
    ExecutionTraceKind.APPROVAL_TIMEOUT,
)

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
    /** Optional parent action used to render a step/tool execution tree. */
    val parentId: String? = null,
    /** Redacted tool arguments; never contains raw secrets or provider payloads. */
    val inputParams: Map<String, String> = emptyMap(),
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
    /** Execution is durably paused until the user decides a side effect. */
    WAITING_FOR_APPROVAL,
    /** The user rejected the pending side effect or its continuation. */
    APPROVAL_CANCELLED,
    /** The approval expired before a decision was received. */
    APPROVAL_TIMEOUT,
    RECOVERING,
    REFLECTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
