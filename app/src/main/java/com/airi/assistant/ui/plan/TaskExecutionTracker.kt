package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TaskExecutionTracker {

    private val stepRegistry = ConcurrentHashMap<String, PlanStepModel>()
    private val stepOrder    = CopyOnWriteArrayList<String>()

    private val _steps = MutableStateFlow<List<PlanStepModel>>(emptyList())
    val steps: StateFlow<List<PlanStepModel>> = _steps.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    fun start(scope: CoroutineScope) {
        ExecutionStatusBus.status
            .onEach { state -> handleUpdate(state) }
            .launchIn(scope)
    }

    private fun handleUpdate(state: com.airi.assistant.ui.viewmodel.AgentState) {
        when (state.executionStage) {
            ExecutionStage.PLANNING -> {
                stepRegistry.clear(); stepOrder.clear()
                val rootId = "goal_root"
                upsert(PlanStepModel(id = rootId, label = state.activeGoalDescription.take(80).ifBlank { "Running task" },
                    status = PlanStepStatus.RUNNING, startedAtMs = System.currentTimeMillis()))
                if (state.nodesTotal > 0) {
                    repeat(state.nodesTotal) { idx ->
                        upsert(PlanStepModel(id = "node_ph_$idx", label = "Step ${idx + 1}", status = PlanStepStatus.QUEUED))
                    }
                }
                _isVisible.value = true
            }
            ExecutionStage.EXECUTING -> {
                val nodeId    = state.activeNodeId.ifBlank { return }
                val nodeLabel = state.activeNodeAction.ifBlank { state.currentAction }
                val placeholder = stepOrder.firstOrNull { id ->
                    stepRegistry[id]?.status == PlanStepStatus.QUEUED && stepRegistry[id]?.label?.startsWith("Step ") == true
                }
                if (placeholder != null) {
                    val updated = stepRegistry[placeholder]!!.copy(id = nodeId, label = nodeLabel.take(60),
                        status = PlanStepStatus.RUNNING, startedAtMs = System.currentTimeMillis())
                    stepRegistry.remove(placeholder)
                    val idx = stepOrder.indexOf(placeholder)
                    if (idx >= 0) stepOrder[idx] = nodeId
                    stepRegistry[nodeId] = updated
                } else {
                    val existing = stepRegistry[nodeId]
                    upsert((existing ?: PlanStepModel(id = nodeId, label = nodeLabel.take(60))).copy(
                        label = nodeLabel.take(60).ifBlank { existing?.label ?: "Processing" },
                        status = PlanStepStatus.RUNNING,
                        startedAtMs = existing?.startedAtMs ?: System.currentTimeMillis()))
                }
            }
            ExecutionStage.RECOVERING -> {
                val existing = stepRegistry[state.activeNodeId] ?: return
                upsert(existing.copy(status = PlanStepStatus.RETRYING, retryCount = state.retryCount,
                    detail = state.recoveryReason.take(120)))
            }
            ExecutionStage.REFLECTING -> markAllRunning(PlanStepStatus.COMPLETED)
            ExecutionStage.COMPLETED  -> { markAllRunning(PlanStepStatus.COMPLETED); _isVisible.value = true }
            ExecutionStage.FAILED     -> { markAllRunning(PlanStepStatus.FAILED);    _isVisible.value = true }
            ExecutionStage.IDLE       -> { /* keep visible — auto-hide handled by ViewModel */ }
        }
        publish()
    }

    fun clear() { stepRegistry.clear(); stepOrder.clear(); _isVisible.value = false; publish() }

    private fun upsert(step: PlanStepModel) {
        if (!stepOrder.contains(step.id)) stepOrder.add(step.id)
        stepRegistry[step.id] = step
    }

    private fun markAllRunning(target: PlanStepStatus) {
        stepOrder.forEach { id ->
            val s = stepRegistry[id] ?: return@forEach
            if (s.status.isActive) stepRegistry[id] = s.copy(status = target, finishedAtMs = System.currentTimeMillis())
        }
    }

    private fun publish() { _steps.value = stepOrder.mapNotNull { stepRegistry[it] } }
}
