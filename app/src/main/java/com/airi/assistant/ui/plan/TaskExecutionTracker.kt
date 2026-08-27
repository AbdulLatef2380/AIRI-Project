package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Projects admitted agent execution events into the user-facing plan timeline.
 *
 * The timeline never invents numbered steps from a maximum-iteration limit.
 * A plan appears only when an execution carries both an execution identifier and
 * a goal, then records actions as the runtime actually begins them.
 */
class TaskExecutionTracker {

    private val stepRegistry = ConcurrentHashMap<String, PlanStepModel>()
    private val stepOrder = CopyOnWriteArrayList<String>()
    private var activeExecutionId: String? = null

    private val _steps = MutableStateFlow<List<PlanStepModel>>(emptyList())
    val steps: StateFlow<List<PlanStepModel>> = _steps.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    fun start(scope: CoroutineScope) {
        ExecutionStatusBus.status
            .onEach(::onExecutionState)
            .launchIn(scope)
    }

    /** Internal for focused regression tests of timeline ownership and states. */
    internal fun onExecutionState(state: AgentState) {
        when (state.executionStage) {
            ExecutionStage.PLANNING -> {
                if (!hasAdmittedPlan(state)) {
                    clear()
                    return
                }
                beginExecution(state)
            }
            ExecutionStage.EXECUTING -> {
                if (!accepts(state)) return
                val nodeId = state.activeNodeId
                val nodeLabel = state.activeNodeAction.trim()
                if (nodeId.isBlank() || nodeLabel.isBlank()) return
                val existing = stepRegistry[nodeId]
                upsert(
                    (existing ?: PlanStepModel(id = nodeId, label = nodeLabel.take(MAX_LABEL_CHARS))).copy(
                        label = nodeLabel.take(MAX_LABEL_CHARS),
                        status = PlanStepStatus.RUNNING,
                        startedAtMs = existing?.startedAtMs ?: System.currentTimeMillis(),
                    )
                )
            }
            ExecutionStage.RECOVERING -> {
                if (!accepts(state) || state.activeNodeId.isBlank()) return
                val existing = stepRegistry[state.activeNodeId] ?: return
                upsert(
                    existing.copy(
                        status = PlanStepStatus.RETRYING,
                        retryCount = state.retryCount,
                        detail = state.recoveryReason.take(MAX_DETAIL_CHARS),
                    )
                )
            }
            ExecutionStage.REFLECTING -> {
                if (!accepts(state)) return
                markAllRunning(PlanStepStatus.COMPLETED)
            }
            ExecutionStage.COMPLETED -> {
                if (!accepts(state)) return
                markAllRunning(PlanStepStatus.COMPLETED)
                _isVisible.value = true
            }
            ExecutionStage.FAILED -> {
                if (!accepts(state)) return
                markAllRunning(PlanStepStatus.FAILED)
                _isVisible.value = true
            }
            ExecutionStage.CANCELLED -> {
                if (!accepts(state)) return
                markAllRunning(PlanStepStatus.CANCELLED)
                _isVisible.value = true
            }
            ExecutionStage.IDLE -> Unit // delayed clear is owned by AgentPlanViewModel
        }
        publish()
    }

    fun clear() {
        stepRegistry.clear()
        stepOrder.clear()
        activeExecutionId = null
        _isVisible.value = false
        publish()
    }

    private fun hasAdmittedPlan(state: AgentState): Boolean =
        state.executionId.isNotBlank() && state.activeGoalDescription.isNotBlank()

    private fun accepts(state: AgentState): Boolean =
        hasAdmittedPlan(state) && activeExecutionId == state.executionId

    private fun beginExecution(state: AgentState) {
        if (activeExecutionId == state.executionId) return
        stepRegistry.clear()
        stepOrder.clear()
        activeExecutionId = state.executionId
        upsert(
            PlanStepModel(
                id = "goal_${state.executionId}",
                label = state.activeGoalDescription.trim().take(MAX_LABEL_CHARS),
                status = PlanStepStatus.RUNNING,
                startedAtMs = System.currentTimeMillis(),
            )
        )
        _isVisible.value = true
    }

    private fun upsert(step: PlanStepModel) {
        if (!stepOrder.contains(step.id)) stepOrder.add(step.id)
        stepRegistry[step.id] = step
    }

    private fun markAllRunning(target: PlanStepStatus) {
        stepOrder.forEach { id ->
            val step = stepRegistry[id] ?: return@forEach
            if (step.status.isActive) {
                stepRegistry[id] = step.copy(status = target, finishedAtMs = System.currentTimeMillis())
            }
        }
    }

    private fun publish() {
        _steps.value = stepOrder.mapNotNull { stepRegistry[it] }
    }

    private companion object {
        const val MAX_LABEL_CHARS = 80
        const val MAX_DETAIL_CHARS = 120
    }
}
