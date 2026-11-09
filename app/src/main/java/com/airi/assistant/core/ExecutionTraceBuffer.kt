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
        activeExecutionId = executionId.trim()
        activeSessionId = sessionId.trim().take(MAX_ID_CHARS)
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
        outputSummary: String? = null,
        provenance: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
        status: ExecutionTraceStatus = kind.defaultStatus(),
    ): ExecutionTraceEvent? {
        if (executionId.isBlank() || executionId != activeExecutionId || summary.isBlank()) return null
        val requiresActionIdentity = kind in setOf(
            ExecutionTraceKind.TOOL_REQUESTED,
            ExecutionTraceKind.TOOL_STARTED,
            ExecutionTraceKind.TOOL_OUTPUT,
            ExecutionTraceKind.TOOL_COMPLETED,
            ExecutionTraceKind.TOOL_FAILED,
            ExecutionTraceKind.TOOL_CANCELLED,
            ExecutionTraceKind.WAITING_FOR_APPROVAL,
        )
        if (requiresActionIdentity && actionId.isNullOrBlank()) return null
        val event = ExecutionTraceEvent(
            executionId = executionId,
            sequence = nextSequence++,
            timestampMs = timestampMs,
            kind = kind,
            status = status,
            summary = summary.trim().take(MAX_SUMMARY_CHARS),
            detail = detail?.trim()?.take(MAX_DETAIL_CHARS),
            actionId = actionId?.trim()?.take(MAX_ID_CHARS)?.takeIf { it.isNotBlank() },
            durationMs = durationMs?.coerceAtLeast(0L),
            errorCode = normalizeErrorCode(errorCode),
            sessionId = activeSessionId,
            machineTag = machineTag?.trim()?.take(MAX_TAG_CHARS)?.takeIf { it.isNotBlank() },
            parentId = parentId?.trim()?.take(MAX_ID_CHARS)?.takeIf { it.isNotBlank() },
            inputParams = inputParams.entries
                .asSequence()
                .filter { it.key.isNotBlank() }
                .take(MAX_INPUT_FIELDS)
                .associate { it.key.trim().take(MAX_FIELD_CHARS) to it.value.take(MAX_FIELD_VALUE_CHARS) },
            outputSummary = outputSummary?.trim()?.take(MAX_DETAIL_CHARS)?.takeIf { it.isNotBlank() },
            provenance = provenance?.trim()?.take(MAX_TAG_CHARS)?.takeIf { it.isNotBlank() },
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
        const val MAX_SUMMARY_CHARS = 240
        const val MAX_DETAIL_CHARS = 800
        const val MAX_ID_CHARS = 160
        const val MAX_TAG_CHARS = 80
        const val MAX_FIELD_CHARS = 80
        const val MAX_FIELD_VALUE_CHARS = 240
        const val MAX_INPUT_FIELDS = 32

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
    ExecutionTraceKind.TOOL_OUTPUT,
    ExecutionTraceKind.VERIFICATION_PASSED,
    ExecutionTraceKind.VERIFICATION_FAILED,
    ExecutionTraceKind.WAITING_FOR_APPROVAL,
    ExecutionTraceKind.RECOVERING,
    ExecutionTraceKind.COMPLETED,
    ExecutionTraceKind.FAILED,
	    ExecutionTraceKind.CANCELLED,
	    ExecutionTraceKind.TIMED_OUT,
	    ExecutionTraceKind.APPROVAL_CANCELLED,
    ExecutionTraceKind.APPROVAL_TIMEOUT,
)

data class ExecutionTraceEvent(
    val executionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val kind: ExecutionTraceKind,
    val status: ExecutionTraceStatus = kind.defaultStatus(),
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
    /** Redacted bounded result evidence; never the raw tool payload. */
    val outputSummary: String? = null,
    /** Safe source label such as local, connector, skill, or terminal. */
    val provenance: String? = null,
) {
    /** Stable correlation key for this ordered event within one execution. */
    val eventId: String get() = "$executionId:event:$sequence"
    /** Stable terminal delivery key; non-terminal events still have a deterministic value. */
    val deliveryId: String get() = "$executionId:delivery:$sequence"
}

enum class ExecutionTraceKind {
    EXECUTION_STARTED,
    PLAN_CREATED,
    REASONING_NOTE,
    TOOL_REQUESTED,
    PLANNING,
    STEP_STARTED,
    STEP_COMPLETED,
    TOOL_STARTED,
    TOOL_OUTPUT,
    TOOL_COMPLETED,
    TOOL_FAILED,
    TOOL_CANCELLED,
    VERIFICATION_STARTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    /** Execution is durably paused until the user decides a side effect. */
    WAITING_FOR_APPROVAL,
    /** The user rejected the pending side effect or its continuation. */
    APPROVAL_CANCELLED,
	    /** The approval expired before a decision was received. */
	    APPROVAL_TIMEOUT,
	    /** The execution exceeded its stall or hard deadline. */
	    TIMED_OUT,
	    RECOVERING,
    REFLECTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class ExecutionTraceStatus {
    PENDING,
    RUNNING,
    WAITING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

private fun ExecutionTraceKind.defaultStatus(): ExecutionTraceStatus = when (this) {
    ExecutionTraceKind.TOOL_REQUESTED -> ExecutionTraceStatus.PENDING
    ExecutionTraceKind.TOOL_STARTED,
    ExecutionTraceKind.EXECUTION_STARTED,
    ExecutionTraceKind.PLAN_CREATED,
    ExecutionTraceKind.REASONING_NOTE,
    ExecutionTraceKind.STEP_STARTED,
    ExecutionTraceKind.REFLECTING,
    ExecutionTraceKind.VERIFICATION_STARTED -> ExecutionTraceStatus.RUNNING
    ExecutionTraceKind.WAITING_FOR_APPROVAL -> ExecutionTraceStatus.WAITING
    ExecutionTraceKind.RECOVERING -> ExecutionTraceStatus.RECOVERING
    ExecutionTraceKind.TOOL_COMPLETED,
    ExecutionTraceKind.STEP_COMPLETED,
    ExecutionTraceKind.TOOL_OUTPUT,
    ExecutionTraceKind.VERIFICATION_PASSED,
    ExecutionTraceKind.COMPLETED -> ExecutionTraceStatus.COMPLETED
    ExecutionTraceKind.TOOL_FAILED,
    ExecutionTraceKind.FAILED,
    ExecutionTraceKind.TIMED_OUT,
    ExecutionTraceKind.VERIFICATION_FAILED,
    ExecutionTraceKind.APPROVAL_TIMEOUT -> ExecutionTraceStatus.FAILED
    ExecutionTraceKind.TOOL_CANCELLED,
    ExecutionTraceKind.CANCELLED,
    ExecutionTraceKind.APPROVAL_CANCELLED -> ExecutionTraceStatus.CANCELLED
    ExecutionTraceKind.PLANNING -> ExecutionTraceStatus.RUNNING
}
