package com.airi.assistant.execution

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Correlation identity shared by every layer participating in one AI request. */
data class ExecutionIdentity(
    val requestId: String = newId("req"),
    val sessionId: String,
    val executionId: String = newId("exec"),
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    fun childToolCallId(stepId: String, attempt: Int): String =
        "$executionId:tool:${stepId.ifBlank { "unknown" }}:$attempt"

    companion object {
        fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    }
}

/** Boundary contract shared by ChatViewModel and AgentLoop. */
object ChatExecutionIdentityContract {
    const val UNKNOWN_SESSION = "session-unknown"

    fun normalizeSessionId(sessionId: String): String =
        sessionId.trim().ifBlank { UNKNOWN_SESSION }

    fun create(sessionId: String, executionId: String): ExecutionIdentity =
        ExecutionIdentity(
            sessionId = normalizeSessionId(sessionId),
            executionId = executionId,
        )
}

enum class ExecutionLifecycleState {
    IDLE, PREPARING, VALIDATING, PREPARING_CONTEXT, WAITING_PERMISSION,
    EXECUTING, STREAMING, TOOL_EXECUTION, WAITING_TOOL,
    RETRYING, PAUSED, RESUMING,
    COMPLETED, FAILED, CANCELLED, TIMEOUT, REJECTED
}

/** Small deterministic state machine; UI and adapters must consume its state, not infer it. */
class ExecutionStateMachine(
    initial: ExecutionLifecycleState = ExecutionLifecycleState.IDLE,
) {
    var state: ExecutionLifecycleState = initial
        private set

    fun transition(next: ExecutionLifecycleState): Boolean {
        if (next == state) return true
        if (next.isTerminal()) return terminal(next)
        if (!isAllowed(state, next)) return false
        state = next
        return true
    }

    fun terminal(next: ExecutionLifecycleState): Boolean {
        if (!next.isTerminal() || state.isTerminal()) return false
        state = next
        return true
    }

    fun reset() {
        state = ExecutionLifecycleState.IDLE
    }

    companion object {
        private val edges = mapOf(
            ExecutionLifecycleState.IDLE to setOf(ExecutionLifecycleState.PREPARING),
            ExecutionLifecycleState.PREPARING to setOf(ExecutionLifecycleState.VALIDATING, ExecutionLifecycleState.REJECTED),
            ExecutionLifecycleState.VALIDATING to setOf(ExecutionLifecycleState.PREPARING_CONTEXT, ExecutionLifecycleState.WAITING_PERMISSION, ExecutionLifecycleState.REJECTED),
            ExecutionLifecycleState.PREPARING_CONTEXT to setOf(ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.REJECTED, ExecutionLifecycleState.FAILED),
            ExecutionLifecycleState.WAITING_PERMISSION to setOf(ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.CANCELLED, ExecutionLifecycleState.REJECTED),
            ExecutionLifecycleState.EXECUTING to setOf(ExecutionLifecycleState.STREAMING, ExecutionLifecycleState.TOOL_EXECUTION, ExecutionLifecycleState.RETRYING, ExecutionLifecycleState.COMPLETED, ExecutionLifecycleState.FAILED, ExecutionLifecycleState.CANCELLED, ExecutionLifecycleState.TIMEOUT),
            ExecutionLifecycleState.STREAMING to setOf(ExecutionLifecycleState.TOOL_EXECUTION, ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.RETRYING, ExecutionLifecycleState.COMPLETED, ExecutionLifecycleState.FAILED, ExecutionLifecycleState.CANCELLED, ExecutionLifecycleState.TIMEOUT),
            ExecutionLifecycleState.TOOL_EXECUTION to setOf(ExecutionLifecycleState.WAITING_TOOL, ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.RETRYING, ExecutionLifecycleState.FAILED, ExecutionLifecycleState.CANCELLED),
            ExecutionLifecycleState.WAITING_TOOL to setOf(ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.CANCELLED, ExecutionLifecycleState.FAILED),
            ExecutionLifecycleState.RETRYING to setOf(ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.CANCELLED, ExecutionLifecycleState.FAILED),
            ExecutionLifecycleState.PAUSED to setOf(ExecutionLifecycleState.RESUMING, ExecutionLifecycleState.CANCELLED),
            ExecutionLifecycleState.RESUMING to setOf(ExecutionLifecycleState.EXECUTING, ExecutionLifecycleState.FAILED),
        )

        private fun isAllowed(from: ExecutionLifecycleState, to: ExecutionLifecycleState): Boolean =
            to in (edges[from] ?: emptySet())

        fun ExecutionLifecycleState.isTerminal(): Boolean = this in setOf(
            ExecutionLifecycleState.COMPLETED,
            ExecutionLifecycleState.FAILED,
            ExecutionLifecycleState.CANCELLED,
            ExecutionLifecycleState.TIMEOUT,
            ExecutionLifecycleState.REJECTED,
        )
    }
}

/** Thread-safe exactly-once guard for terminal callbacks/events. */
class TerminalDeliveryGuard {
    private val delivered = AtomicBoolean(false)
    fun tryDeliver(): Boolean = delivered.compareAndSet(false, true)
    fun wasDelivered(): Boolean = delivered.get()
}

data class ToolCallFingerprint(
    val executionId: String,
    val toolName: String,
    val argumentsHash: String,
    val parentStepId: String,
)

/** Allows legitimate calls with changed arguments/steps while blocking exact repeats. */
class ToolCallLedger {
    private val completed = ConcurrentHashMap.newKeySet<ToolCallFingerprint>()

    fun shouldExecute(call: ToolCallFingerprint): Boolean = !completed.contains(call)
    fun markCompleted(call: ToolCallFingerprint): Boolean = completed.add(call)
    fun wasCompleted(call: ToolCallFingerprint): Boolean = completed.contains(call)
    fun clearExecution(executionId: String) {
        completed.removeIf { it.executionId == executionId }
    }
}

internal fun stableArgumentsHash(arguments: String): String =
    arguments.trim().hashCode().toUInt().toString(16)
