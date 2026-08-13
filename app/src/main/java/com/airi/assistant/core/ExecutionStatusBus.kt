package com.airi.assistant.core

import android.util.Log
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

    // : Wrap status with FlowPressureMonitor to detect slow collectors.
    // monitorFlow wraps the Flow at the read-side; _status remains a MutableStateFlow
    // for internal writes. Backpressure events log to RuntimeProfiler ().
    val status: StateFlow<AgentState> = _status.asStateFlow()
    // Wrapped version for collectors that want pressure detection:
    val monitoredStatus = FlowPressureMonitor.monitorFlow("ExecutionStatusBus", _status)

    // ── Write API (called by UCL / orchestrators) ─────────────────────────────

    /** Signal that a DAG graph execution has started. */
    fun onGraphStarted(goalDescription: String, totalNodes: Int) {
        _status.value = AgentState(
            isWorking             = true,
            currentAction         = goalDescription.take(80),
            totalSteps            = totalNodes,
            activeGoalDescription = goalDescription,
            nodesTotal            = totalNodes,
            executionStage        = ExecutionStage.PLANNING
        )
        Log.i(TAG, "EXEC_STATUS_PLANNING goalChars=${goalDescription.length} nodes=$totalNodes")
    }

    /** Signal that a specific node wave has started executing. */
    fun onWaveStarted(nodeIds: List<String>, nodeActions: List<String>) {
        _status.update { current ->
            current.copy(
                executionStage  = ExecutionStage.EXECUTING,
                activeNodeId    = nodeIds.firstOrNull() ?: "",
                activeNodeAction = nodeActions.firstOrNull() ?: "",
                currentAction   = nodeActions.joinToString(", ").take(80)
            )
        }
    }

    /** Signal that a node has completed successfully. */
    fun onNodeCompleted(nodeId: String, nodesCompleted: Int) {
        _status.update { current ->
            current.copy(
                nodesCompleted = nodesCompleted,
                currentStep    = nodesCompleted,
                activeNodeId   = nodeId
            )
        }
    }

    /** Signal that a node failed and recovery is in progress. */
    fun onNodeRecovering(nodeId: String, reason: String, retryCount: Int) {
        _status.update { current ->
            current.copy(
                executionStage = ExecutionStage.RECOVERING,
                activeNodeId   = nodeId,
                recoveryReason = reason.take(120),
                retryCount     = retryCount,
                currentAction  = "Recovering: ${reason.take(60)}"
            )
        }
        Log.i(TAG, "EXEC_STATUS_RECOVERING node=$nodeId attempt=$retryCount reasonChars=${reason.length}")
    }

    /** Signal that reflection is running post-graph. */
    fun onReflecting() {
        _status.update { it.copy(executionStage = ExecutionStage.REFLECTING, currentAction = "Analysing results...") }
    }

    /** Signal graph completion. */
    fun onGraphCompleted(success: Boolean) {
        val stage = if (success) ExecutionStage.COMPLETED else ExecutionStage.FAILED
        _status.update { current ->
            current.copy(
                isWorking      = false,
                executionStage = stage,
                currentAction  = if (success) "Done" else "Execution failed",
                activeNodeId   = "",
                activeNodeAction = ""
            )
        }
        Log.i(TAG, "EXEC_STATUS ${stage.name}")
    }

    /** Reset to idle (e.g. on session clear or ViewModel cleared). */
    fun reset() {
        _status.value = AgentState()
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    private fun MutableStateFlow<AgentState>.update(transform: (AgentState) -> AgentState) {
        value = transform(value)
    }
}
