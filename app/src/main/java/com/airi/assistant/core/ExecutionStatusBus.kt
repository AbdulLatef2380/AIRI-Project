package com.airi.assistant.core

import android.util.Log
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.runtime.profiler.FlowPressureMonitor
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _status = MutableStateFlow(AgentState())
    private val traceBuffer = ExecutionTraceBuffer()
    private val toolTraceLifecycle = ExecutionToolTraceLifecycle()
    private val _trace = MutableStateFlow<List<ExecutionTraceEvent>>(emptyList())

    // : Wrap status with FlowPressureMonitor to detect slow collectors.
    // monitorFlow wraps the Flow at the read-side; _status remains a MutableStateFlow
    // for internal writes. Backpressure events log to RuntimeProfiler ().
    val status: StateFlow<AgentState> = _status.asStateFlow()
    /** Ordered, bounded, user-safe summaries for the active execution trace. */
    val trace: StateFlow<List<ExecutionTraceEvent>> = _trace.asStateFlow()
    // Wrapped version for collectors that want pressure detection:
    val monitoredStatus = FlowPressureMonitor.monitorFlow("ExecutionStatusBus", _status)

    // ── Write API (called by UCL / orchestrators) ─────────────────────────────

    /** Signal that a DAG graph execution has started. */
    fun onGraphStarted(goalDescription: String, totalNodes: Int, executionId: String) {
        if (executionId.isBlank()) {
            Log.w(TAG, "EXEC_STATUS_REJECTED missing executionId")
            return
        }
        _status.value = AgentState(
            isWorking             = true,
            currentAction         = goalDescription.take(80),
            totalSteps            = totalNodes,
            activeGoalDescription = goalDescription,
            executionId           = executionId,
            nodesTotal            = totalNodes,
            executionStage        = ExecutionStage.PLANNING
        )
        traceBuffer.begin(executionId)
        toolTraceLifecycle.begin(executionId)
        appendTrace(executionId, ExecutionTraceKind.PLANNING, "Planning task", goalDescription)
        Log.i(TAG, "EXEC_STATUS_PLANNING goalChars=${goalDescription.length} nodes=$totalNodes")
    }

    /** Signal that a specific node wave has started executing. */
    fun onWaveStarted(
        nodeIds: List<String>,
        nodeActions: List<String>,
        executionId: String = "",
    ) {
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) return@update current
            current.copy(
                executionStage  = ExecutionStage.EXECUTING,
                activeNodeId    = nodeIds.firstOrNull() ?: "",
                activeNodeAction = nodeActions.firstOrNull() ?: "",
                currentAction   = nodeActions.joinToString(", ").take(80)
            )
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, ExecutionTraceKind.STEP_STARTED, "Executing step", nodeActions.firstOrNull())
        }
    }

    /** Signal that a node has completed successfully. */
    fun onNodeCompleted(
        nodeId: String,
        nodesCompleted: Int,
        executionId: String = "",
    ) {
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) return@update current
            current.copy(
                nodesCompleted = nodesCompleted,
                currentStep    = nodesCompleted,
                activeNodeId   = nodeId
            )
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, ExecutionTraceKind.STEP_COMPLETED, "Step completed", nodeId)
        }
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
    ) {
        if (!acceptsEvent(_status.value.executionId, executionId) ||
            !toolTraceLifecycle.admitStart(executionId, actionId)
        ) {
            Log.w(TAG, "TRACE_TOOL_START_REJECTED owner=${executionId.take(8)}")
            return
        }
        appendTrace(
            executionId = executionId,
            kind = ExecutionTraceKind.TOOL_STARTED,
            summary = "Running tool: $toolName",
            detail = detail,
            actionId = actionId,
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
    ) = onToolTerminal(
        executionId = executionId,
        actionId = actionId,
        toolName = toolName,
        kind = ExecutionTraceKind.TOOL_FAILED,
        summaryPrefix = "Tool failed",
        durationMs = durationMs,
        detail = detail,
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
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) return@update current
            current.copy(
                executionStage = ExecutionStage.RECOVERING,
                activeNodeId   = nodeId,
                recoveryReason = reason.take(120),
                retryCount     = retryCount,
                currentAction  = "Recovering: ${reason.take(60)}"
            )
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, ExecutionTraceKind.RECOVERING, "Recovering step", reason)
        }
        Log.i(TAG, "EXEC_STATUS_RECOVERING node=$nodeId attempt=$retryCount reasonChars=${reason.length}")
    }

    /** Signal that reflection is running post-graph. */
    fun onReflecting(executionId: String = "") {
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) current
            else current.copy(executionStage = ExecutionStage.REFLECTING, currentAction = "Analysing results...")
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, ExecutionTraceKind.REFLECTING, "Reviewing execution result")
        }
    }

    /** Signal graph completion. */
    fun onGraphCompleted(
        success: Boolean,
        executionId: String = "",
    ) {
        val stage = if (success) ExecutionStage.COMPLETED else ExecutionStage.FAILED
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) return@update current
            current.copy(
                isWorking      = false,
                executionStage = stage,
                currentAction  = if (success) "Done" else "Execution failed",
                activeNodeId   = "",
                activeNodeAction = ""
            )
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, if (success) ExecutionTraceKind.COMPLETED else ExecutionTraceKind.FAILED,
                if (success) "Execution completed" else "Execution failed")
        }
        Log.i(TAG, "EXEC_STATUS ${stage.name}")
    }

    /** Signal explicit user or lifecycle cancellation of the active graph. */
    fun onGraphCancelled(executionId: String) {
        _status.update { current ->
            if (!belongsToActiveExecution(current, executionId)) return@update current
            current.copy(
                isWorking = false,
                executionStage = ExecutionStage.CANCELLED,
                currentAction = "Cancelled",
                activeNodeId = "",
                activeNodeAction = "",
            )
        }
        if (acceptsEvent(_status.value.executionId, executionId)) {
            appendTrace(executionId, ExecutionTraceKind.CANCELLED, "Execution cancelled")
        }
        Log.i(TAG, "EXEC_STATUS_CANCELLED")
    }

    /** Reset to idle (e.g. on session clear or ViewModel cleared). */
    fun reset() {
        _status.value = AgentState()
    }

    private fun onToolTerminal(
        executionId: String,
        actionId: String,
        toolName: String,
        kind: ExecutionTraceKind,
        summaryPrefix: String,
        durationMs: Long,
        detail: String?,
    ) {
        if (!acceptsEvent(_status.value.executionId, executionId) ||
            !toolTraceLifecycle.admitTerminal(executionId, actionId)
        ) {
            Log.w(TAG, "TRACE_TOOL_TERMINAL_REJECTED owner=${executionId.take(8)}")
            return
        }
        appendTrace(
            executionId = executionId,
            kind = kind,
            summary = "$summaryPrefix: $toolName",
            detail = detail,
            actionId = actionId,
            durationMs = durationMs,
        )
    }

    private fun appendTrace(
        executionId: String,
        kind: ExecutionTraceKind,
        summary: String,
        detail: String? = null,
        actionId: String? = null,
        durationMs: Long? = null,
    ) {
        val event = traceBuffer.append(
            executionId = executionId,
            kind = kind,
            summary = PrivacyGuard.redactForTrace(summary),
            detail = detail?.let { PrivacyGuard.redactForTrace(it) },
            actionId = actionId,
            durationMs = durationMs,
        ) ?: return
        _trace.value = traceBuffer.snapshot()
        Log.d(TAG, "TRACE kind=${event.kind} seq=${event.sequence} execution=${event.executionId.take(8)}")
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    internal fun acceptsEvent(activeExecutionId: String, eventExecutionId: String): Boolean =
        activeExecutionId.isNotBlank() && eventExecutionId.isNotBlank() &&
            activeExecutionId == eventExecutionId

    private fun belongsToActiveExecution(current: AgentState, executionId: String): Boolean =
        acceptsEvent(current.executionId, executionId)

    private fun MutableStateFlow<AgentState>.update(transform: (AgentState) -> AgentState) {
        value = transform(value)
    }
}
