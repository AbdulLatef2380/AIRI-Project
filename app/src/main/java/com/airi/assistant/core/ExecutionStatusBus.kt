package com.airi.assistant.core

import android.util.Log
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.runtime.profiler.FlowPressureMonitor
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import com.airi.assistant.ui.viewmodel.FinalAnswerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * ExecutionStatusBus — decoupled live execution status channel.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Bridges the autonomous execution layer (UnifiedCognitiveLoop, orchestrators)
 * with the UI layer (ChatViewModel) without introducing a direct dependency.
 *
 * Pattern: singleton observable bus.
 *   - Writers: UCL.executeGraph(), ProductionAgentOrchestrator, repatchNode()
 *   - Readers: ChatViewModel (collects via viewModelScope.launch)
 *
 * ── WHY NOT INJECT DIRECTLY ───────────────────────────────────────────────────
 * UCL is instantiated in multiple contexts (accessibility service, agent executor,
 * ViewModel) and does NOT have access to the ViewModel lifecycle. A static bus
 * avoids the lifecycle mismatch while keeping the consumer loosely coupled.
 *
 * ── THREAD SAFETY ─────────────────────────────────────────────────────────────
 * MutableStateFlow is thread-safe. All update methods are safe to call from any
 * dispatcher (Dispatchers.IO, Default, or Main).
 */
object ExecutionStatusBus {

    private const val TAG = "ExecutionStatusBus"
    /** Executions whose trace is retained; set through [TraceRetention]. */
    @Volatile internal var maxTraceHistory = 32

    private val _status = MutableStateFlow(AgentState())
    private val _trace = MutableStateFlow<List<ExecutionTraceEvent>>(emptyList())
    private val traceLock = Any()
    private val traceHistory = linkedMapOf<String, List<ExecutionTraceEvent>>()
    private val traceBuffers = linkedMapOf<String, ExecutionTraceBuffer>()
    // Tool admission is queried both inside and outside traceLock by terminal
    // callbacks. A concurrent map prevents two executions from racing on the
    // registry while each lifecycle remains synchronized internally.
    private val toolTraceLifecycles = ConcurrentHashMap<String, ExecutionToolTraceLifecycle>()
    private val terminalExecutions = mutableSetOf<String>()
    /** Started-tool metadata carried forward to its terminal event. */
    private val toolTraceContexts = mutableMapOf<String, MutableMap<String, ToolTraceContext>>()
    /** Live-only approval correlation; durable records remain free of runtime/UI state. */
    private val approvalOwners = mutableMapOf<String, String>()

    // : Wrap status with FlowPressureMonitor to detect slow collectors.
    // monitorFlow wraps the Flow at the read-side; _status remains a MutableStateFlow
    // for internal writes. Backpressure events log to RuntimeProfiler ().
    val status: StateFlow<AgentState> = _status.asStateFlow()
    /** Ordered, bounded, user-safe summaries for the active execution trace. */
    val trace: StateFlow<List<ExecutionTraceEvent>> = _trace.asStateFlow()

    /** Snapshot retained by execution identity, even after another run becomes active. */
    fun traceSnapshot(executionId: String): List<ExecutionTraceEvent> = synchronized(traceLock) {
        if (executionId.isBlank()) emptyList()
        else traceHistory[executionId]?.toList()
            ?: traceBuffers[executionId]?.snapshot().orEmpty()
    }
    // Wrapped version for collectors that want pressure detection:
    val monitoredStatus = FlowPressureMonitor.monitorFlow("ExecutionStatusBus", _status)

    // ── Write API (called by UCL / orchestrators) ─────────────────────────────

    /** Signal that a DAG graph execution has started. */
    fun onGraphStarted(
        goalDescription: String,
        totalNodes: Int,
        executionId: String,
        sessionId: String = "",
    ) {
        if (executionId.isBlank()) {
            safeLog { Log.w(TAG, "EXEC_STATUS_REJECTED missing executionId") }
            return
        }
        synchronized(traceLock) {
            _status.update {
                AgentState(
                    isWorking             = true,
                    currentAction         = goalDescription.take(80),
                    totalSteps            = totalNodes,
                    activeGoalDescription = goalDescription,
                    executionId           = executionId,
                    sessionId             = sessionId,
                    nodesTotal            = totalNodes,
                    executionStage        = ExecutionStage.PLANNING
                )
            }
            traceBuffers[executionId] = ExecutionTraceBuffer().also { it.begin(executionId, sessionId) }
            toolTraceLifecycles[executionId] = ExecutionToolTraceLifecycle().also { it.begin(executionId) }
            toolTraceContexts[executionId] = mutableMapOf()
            terminalExecutions.remove(executionId)
            traceHistory[executionId] = emptyList()
            while (traceHistory.size > maxTraceHistory) {
                val evicted = traceHistory.keys.first()
                traceHistory.remove(evicted)
                traceBuffers.remove(evicted)
                toolTraceLifecycles.remove(evicted)
                toolTraceContexts.remove(evicted)
                terminalExecutions.remove(evicted)
            }
        }
        appendTrace(executionId, ExecutionTraceKind.PLANNING, "Planning task", goalDescription, machineTag = "planning")
        safeLog { Log.i(TAG, "EXEC_STATUS_PLANNING goalChars=${goalDescription.length} nodes=$totalNodes") }
    }

    /** Signal that a specific node wave has started executing. */
    fun onWaveStarted(
        nodeIds: List<String>,
        nodeActions: List<String>,
        executionId: String = "",
    ) {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) return@statusUpdate current
            current.copy(
                executionStage  = ExecutionStage.EXECUTING,
                activeNodeId    = nodeIds.firstOrNull() ?: "",
                activeNodeAction = nodeActions.firstOrNull() ?: "",
                currentAction   = nodeActions.joinToString(", ").take(80)
            )
        }
        if (isOpenExecution(executionId)) {
            nodeIds.forEachIndexed { index, nodeId ->
                appendTrace(
                    executionId,
                    ExecutionTraceKind.STEP_STARTED,
                    "Executing step",
                    nodeActions.getOrNull(index),
                    actionId = nodeId,
                    machineTag = "step_started",
                )
            }
        }
    }

    /** Signal that a node has completed successfully. */
    fun onNodeCompleted(
        nodeId: String,
        nodesCompleted: Int,
        executionId: String = "",
    ) {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) return@statusUpdate current
            current.copy(
                nodesCompleted = nodesCompleted,
                currentStep    = nodesCompleted,
                activeNodeId   = nodeId
            )
        }
        if (isOpenExecution(executionId)) {
            appendTrace(
                executionId,
                ExecutionTraceKind.STEP_COMPLETED,
                "Step completed",
                nodeId,
                actionId = nodeId,
                machineTag = "step_completed",
            )
        }
    }

    /** Pause the owning execution until the user decides a durable side effect. */
    fun onApprovalWaiting(
        executionId: String,
        actionId: String,
        interruptId: String,
        actionDescription: String,
        expiresAtMs: Long? = null,
    ) {
        if (!belongsToActiveExecution(_status.value, executionId) || actionId.isBlank() || interruptId.isBlank()) return
        synchronized(traceLock) { approvalOwners[interruptId] = executionId }
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) current else current.copy(
                isWorking = false,
                currentAction = "Waiting for approval",
                waitingForApproval = true,
                approvalInterruptId = interruptId,
                activeNodeAction = actionDescription.take(120),
            )
        }
        appendTrace(
            executionId = executionId,
            kind = ExecutionTraceKind.WAITING_FOR_APPROVAL,
            summary = "Approval required",
            detail = buildString {
                append(actionDescription.take(160))
                expiresAtMs?.let { append("; expiresAtMs=$it") }
            },
            actionId = actionId,
            machineTag = "approval_waiting",
        )
    }

    /** Resume only the execution that owns the exact approval interrupt. */
    fun onApprovalResumed(executionId: String, interruptId: String) {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId) || current.approvalInterruptId != interruptId) current else current.copy(
                isWorking = true,
                currentAction = "Resuming approved action",
                waitingForApproval = false,
                approvalInterruptId = "",
            )
        }
        synchronized(traceLock) { approvalOwners.remove(interruptId) }
    }

    /** Resume a live approval using only its durable approval identifier. */
    fun onApprovalResumed(interruptId: String) {
        val executionId = synchronized(traceLock) { approvalOwners[interruptId] } ?: return
        onApprovalResumed(executionId, interruptId)
    }

    /** Close a pending approval as a user rejection/cancellation. */
    fun onApprovalCancelled(executionId: String, actionId: String, interruptId: String, reason: String) {
        var accepted = false
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId) || current.approvalInterruptId != interruptId) current else {
                accepted = true
                current.copy(isWorking = false, executionStage = ExecutionStage.CANCELLED, currentAction = "Approval cancelled", waitingForApproval = false, approvalInterruptId = "")
            }
        }
        if (accepted) {
            synchronized(traceLock) { approvalOwners.remove(interruptId) }
            appendTrace(executionId, ExecutionTraceKind.APPROVAL_CANCELLED, "Approval cancelled", reason.take(160), actionId = actionId, machineTag = "approval_cancelled")
        }
    }

    /** Cancel a live approval using only its durable approval identifier. */
    fun onApprovalCancelled(interruptId: String, actionId: String, reason: String) {
        val executionId = synchronized(traceLock) { approvalOwners[interruptId] } ?: return
        onApprovalCancelled(executionId, actionId, interruptId, reason)
    }

    /** Close a pending approval as a timeout; it is never reported as success. */
    fun onApprovalTimedOut(executionId: String, actionId: String, interruptId: String, reason: String = "Approval expired") {
        var accepted = false
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId) || current.approvalInterruptId != interruptId) current else {
                accepted = true
                current.copy(isWorking = false, executionStage = ExecutionStage.FAILED, currentAction = "Approval expired", waitingForApproval = false, approvalInterruptId = "")
            }
        }
        if (accepted) {
            synchronized(traceLock) { approvalOwners.remove(interruptId) }
            appendTrace(executionId, ExecutionTraceKind.APPROVAL_TIMEOUT, "Approval expired", reason.take(160), actionId = actionId, errorCode = "approval_timeout", machineTag = "approval_timeout")
        }
    }

    /** Timeout a live approval using only its durable approval identifier. */
    fun onApprovalTimedOut(interruptId: String, actionId: String, reason: String = "Approval expired") {
        val executionId = synchronized(traceLock) { approvalOwners[interruptId] } ?: return
        onApprovalTimedOut(executionId, actionId, interruptId, reason)
    }

    /**
     * Adds a tool start event only when it belongs to the active execution and
     * has a fresh action identity. The dispatcher must pass the execution id it
     * received from the execution runtime; this method never infers it from UI state.
     */
    fun onToolStarted(
        executionId: String,
        actionId: String,
        toolName: String,
        detail: String? = null,
        inputParams: Map<String, String> = emptyMap(),
        parentId: String? = null,
    ) {
        if (!isOpenExecution(executionId) ||
            (_status.value.executionId == executionId && _status.value.cancellationRequested) ||
            toolTraceLifecycles[executionId]?.admitStart(executionId, actionId) != true
        ) {
            safeLog { Log.w(TAG, "TRACE_TOOL_START_REJECTED owner=${executionId.take(8)}") }
            return
        }
        val redactedParams = inputParams.mapValues { (key, value) -> PrivacyGuard.redactTraceField(key, value) }
        synchronized(traceLock) {
            toolTraceContexts[executionId]?.set(
                actionId,
                ToolTraceContext(redactedParams, parentId?.takeIf { it.isNotBlank() })
            )
        }
        appendTrace(
            executionId = executionId,
            kind = ExecutionTraceKind.TOOL_STARTED,
            summary = "Running tool: $toolName",
            detail = detail,
            actionId = actionId,
            machineTag = "tool_call",
            parentId = parentId,
            inputParams = redactedParams,
        )
    }

    /** Adds the single successful terminal event for an owned tool action. */
    fun onToolCompleted(
        executionId: String,
        actionId: String,
        toolName: String,
        durationMs: Long,
        detail: String? = null,
    ) = onToolTerminal(
        executionId = executionId,
        actionId = actionId,
        toolName = toolName,
        kind = ExecutionTraceKind.TOOL_COMPLETED,
        summaryPrefix = "Tool completed",
        durationMs = durationMs,
        detail = detail,
    )

    /** Adds the single failed terminal event for an owned tool action. */
    fun onToolFailed(
        executionId: String,
        actionId: String,
        toolName: String,
        durationMs: Long,
        detail: String? = null,
        errorCode: String? = null,
    ) = onToolTerminal(
        executionId = executionId,
        actionId = actionId,
        toolName = toolName,
        kind = ExecutionTraceKind.TOOL_FAILED,
        summaryPrefix = "Tool failed",
        durationMs = durationMs,
        detail = detail,
        errorCode = errorCode,
    )

    /** Adds the single cancelled terminal event for an owned tool action. */
    fun onToolCancelled(
        executionId: String,
        actionId: String,
        toolName: String,
        durationMs: Long,
        detail: String? = null,
    ) = onToolTerminal(
        executionId = executionId,
        actionId = actionId,
        toolName = toolName,
        kind = ExecutionTraceKind.TOOL_CANCELLED,
        summaryPrefix = "Tool cancelled",
        durationMs = durationMs,
        detail = detail,
    )

    /** Signal that a node failed and recovery is in progress. */
    fun onNodeRecovering(
        nodeId: String,
        reason: String,
        retryCount: Int,
        executionId: String = "",
    ) {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) return@statusUpdate current
            current.copy(
                executionStage = ExecutionStage.RECOVERING,
                activeNodeId   = nodeId,
                recoveryReason = reason.take(120),
                retryCount     = retryCount,
                waitingForApproval = false,
                approvalInterruptId = "",
                currentAction  = "Recovering: ${reason.take(60)}"
            )
        }
        if (isOpenExecution(executionId)) {
            appendTrace(executionId, ExecutionTraceKind.RECOVERING, "Recovering step", reason, actionId = nodeId, machineTag = "step_recovering")
        }
        safeLog { Log.i(TAG, "EXEC_STATUS_RECOVERING node=$nodeId attempt=$retryCount reasonChars=${reason.length}") }
    }

    /** Signal that reflection is running post-graph. */
    fun onReflecting(executionId: String = "") {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) current
            else current.copy(executionStage = ExecutionStage.REFLECTING, currentAction = "Analysing results...")
        }
        if (isOpenExecution(executionId)) {
            appendTrace(executionId, ExecutionTraceKind.REFLECTING, "Reviewing execution result")
        }
    }

    /** Signal graph completion. */
    fun onGraphCompleted(
        success: Boolean,
        executionId: String = "",
    ) {
        val stage = if (success) ExecutionStage.COMPLETED else ExecutionStage.FAILED
        var accepted = false
        var blockedByApproval = false
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) return@statusUpdate current
            if (current.waitingForApproval) {
                blockedByApproval = true
                return@statusUpdate current
            }
            accepted = true
            current.copy(
                isWorking      = false,
                executionStage = stage,
                currentAction  = if (success) "Done" else "Execution failed",
                activeNodeId   = "",
                activeNodeAction = "",
                waitingForApproval = false,
                approvalInterruptId = "",
            )
        }
        if (!blockedByApproval && (accepted || isOpenExecution(executionId))) {
            appendTrace(
                executionId,
                if (success) ExecutionTraceKind.COMPLETED else ExecutionTraceKind.FAILED,
                if (success) "Execution completed" else "Execution failed",
                machineTag = if (success) "execution_completed" else "execution_failed",
            )
            synchronized(traceLock) { terminalExecutions += executionId }
        }
        safeLog { Log.i(TAG, "EXEC_STATUS ${stage.name}") }
    }

    /**
     * Closes an execution that exceeded its deadline without collapsing the
     * outcome into a generic FAILED event. The UI still uses FAILED as its
     * terminal presentation, while the trace retains the machine-readable
     * timeout distinction and rejects all late events.
     */
    fun onGraphTimedOut(
        executionId: String,
        reason: String = "Execution timed out",
    ) {
        var accepted = false
        statusUpdate { current ->
            if (!acceptsEvent(current.executionId, executionId) ||
                current.executionStage in TERMINAL_STAGES
            ) return@statusUpdate current
            accepted = true
            current.copy(
                isWorking = false,
                executionStage = ExecutionStage.FAILED,
                currentAction = "Execution timed out",
                activeNodeId = "",
                activeNodeAction = "",
                cancellationRequested = false,
                waitingForApproval = false,
                approvalInterruptId = "",
            )
        }
        if (accepted || isOpenExecution(executionId)) {
            appendTrace(
                executionId = executionId,
                kind = ExecutionTraceKind.TIMED_OUT,
                summary = "Execution timed out",
                detail = reason.take(160),
                errorCode = "execution_timeout",
                machineTag = "execution_timeout",
            )
            synchronized(traceLock) { terminalExecutions += executionId }
        }
        safeLog { Log.i(TAG, "EXEC_STATUS_TIMED_OUT") }
    }

    /**
     * Records the user's stop request without pretending that cancellation has
     * already completed. While this flag is set, late node/tool events for the
     * same execution are rejected until [onGraphCancelled] closes the owner.
     */
    fun onGraphCancellationRequested(executionId: String) {
        statusUpdate { current ->
            if (!acceptsEvent(current.executionId, executionId) ||
                !current.isWorking ||
                current.cancellationRequested
            ) {
                current
            } else {
                current.copy(
                    currentAction = "Stopping…",
                    cancellationRequested = true,
                )
            }
        }
        safeLog { Log.i(TAG, "EXEC_STATUS_CANCELLATION_REQUESTED") }
    }

    /** Publishes a compact, user-safe final verification result for ChatScreen. */
    fun onFinalAnswerVerified(
        verified: Boolean,
        issueCount: Int,
        details: List<String> = emptyList(),
        executionId: String = ""
    ) {
        statusUpdate { current ->
            if (!belongsToActiveExecution(current, executionId)) return@statusUpdate current
            current.copy(
                finalAnswerVerification = FinalAnswerUiState(
                    verified = verified,
                    issueCount = issueCount.coerceAtLeast(0),
                    details = details.take(3).map { it.take(160) }
                )
            )
        }
    }

    /** Signal explicit user or lifecycle cancellation of the active graph. */
    fun onGraphCancelled(executionId: String) {
        var accepted = false
        statusUpdate { current ->
            if (!acceptsEvent(current.executionId, executionId) ||
                current.executionStage in TERMINAL_STAGES
            ) return@statusUpdate current
            accepted = true
            current.copy(
                isWorking = false,
                executionStage = ExecutionStage.CANCELLED,
                currentAction = "Cancelled",
                activeNodeId = "",
                activeNodeAction = "",
                cancellationRequested = false,
                waitingForApproval = false,
                approvalInterruptId = "",
            )
        }
        if (accepted || isOpenExecution(executionId)) {
            appendTrace(executionId, ExecutionTraceKind.CANCELLED, "Execution cancelled", machineTag = "execution_cancelled")
            synchronized(traceLock) { terminalExecutions += executionId }
        }
        safeLog { Log.i(TAG, "EXEC_STATUS_CANCELLED") }
    }

    /** Reset to idle (e.g. on session clear or ViewModel cleared). */
    fun reset() {
        synchronized(traceLock) {
            _status.update { AgentState() }
            traceHistory.clear()
            traceBuffers.clear()
            toolTraceLifecycles.clear()
            toolTraceContexts.clear()
            terminalExecutions.clear()
            approvalOwners.clear()
        }
        _trace.update { emptyList() }
    }

    private fun onToolTerminal(
        executionId: String,
        actionId: String,
        toolName: String,
        kind: ExecutionTraceKind,
        summaryPrefix: String,
        durationMs: Long,
        detail: String?,
        errorCode: String? = null,
    ) {
        if (!belongsToToolTerminalExecution(_status.value, executionId) ||
            !isOpenExecution(executionId) ||
            toolTraceLifecycles[executionId]?.admitTerminal(executionId, actionId) != true
        ) {
            safeLog { Log.w(TAG, "TRACE_TOOL_TERMINAL_REJECTED owner=${executionId.take(8)}") }
            return
        }
        val context = synchronized(traceLock) {
            toolTraceContexts[executionId]?.remove(actionId)
        }
        appendTrace(
            executionId = executionId,
            kind = kind,
            summary = "$summaryPrefix: $toolName",
            detail = detail,
            actionId = actionId,
            durationMs = durationMs,
            errorCode = errorCode,
            machineTag = "tool_call",
            parentId = context?.parentId,
            inputParams = context?.inputParams.orEmpty(),
        )
    }

    private fun appendTrace(
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
    ) {
        val normalizedErrorCode = ExecutionTraceBuffer.normalizeErrorCode(errorCode)
        val event = synchronized(traceLock) {
            val buffer = traceBuffers[executionId] ?: return
            if (executionId in terminalExecutions) return
            val appended = buffer.append(
                executionId = executionId,
                kind = kind,
                summary = PrivacyGuard.redactForTrace(summary),
                detail = if (normalizedErrorCode != null || detail != null) {
                    val typed = normalizedErrorCode?.let { "code=$it; " }.orEmpty()
                    PrivacyGuard.redactForTrace(typed + detail.orEmpty())
                } else null,
                actionId = actionId,
                durationMs = durationMs,
                errorCode = normalizedErrorCode,
                machineTag = machineTag,
                parentId = parentId,
                inputParams = inputParams,
            ) ?: return
            val currentSnapshot = buffer.snapshot()
            traceHistory[executionId] = currentSnapshot
            while (traceHistory.size > maxTraceHistory) {
                traceHistory.remove(traceHistory.keys.first())
            }
            if (_status.value.executionId == executionId) {
                _trace.update { currentSnapshot }
            }
            appended
        }
        safeLog { Log.d(TAG, "TRACE kind=${event.kind} seq=${event.sequence} execution=${event.executionId.take(8)}") }
    }

    /** Serialize status transitions with trace ownership updates. */
    private inline fun statusUpdate(crossinline transform: (AgentState) -> AgentState) {
        synchronized(traceLock) { _status.update(transform) }
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    internal fun acceptsEvent(activeExecutionId: String, eventExecutionId: String): Boolean =
        activeExecutionId.isNotBlank() && eventExecutionId.isNotBlank() &&
            activeExecutionId == eventExecutionId

    internal fun belongsToSession(activeSessionId: String, eventSessionId: String): Boolean =
        activeSessionId.isNotBlank() && eventSessionId.isNotBlank() &&
            activeSessionId == eventSessionId

    private fun belongsToActiveExecution(current: AgentState, executionId: String): Boolean =
        acceptsEvent(current.executionId, executionId) &&
            current.executionStage !in TERMINAL_STAGES &&
            !current.cancellationRequested

    /**
     * A stop request blocks new work, but an already-started tool must still
     * publish its terminal cancellation so the trace cannot contain a dangling
     * TOOL_STARTED event forever.
     */
    private fun belongsToToolTerminalExecution(current: AgentState, executionId: String): Boolean =
        executionId.isNotBlank() && current.executionStage !in TERMINAL_STAGES

    private fun isOpenExecution(executionId: String): Boolean = synchronized(traceLock) {
        executionId.isNotBlank() && executionId in traceBuffers && executionId !in terminalExecutions
    }

    private data class ToolTraceContext(
        val inputParams: Map<String, String>,
        val parentId: String?,
    )

    private val TERMINAL_STAGES = setOf(
        ExecutionStage.COMPLETED,
        ExecutionStage.FAILED,
        ExecutionStage.CANCELLED,
    )

    /** Observability must never break execution, including local JVM tests. */
    private inline fun safeLog(block: () -> Int) {
        runCatching { block() }
    }
}
