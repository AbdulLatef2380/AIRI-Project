package com.airi.assistant.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentPlanViewModel(application: Application) : AndroidViewModel(application) {
    private val tracker = TaskExecutionTracker()
    private val snapshotStore = PlanSnapshotStore(application)
    private val _planContext = MutableStateFlow(PlanContext())
    val planContext: StateFlow<PlanContext> = _planContext.asStateFlow()
    private val _restoredPlan = MutableStateFlow(false)

    val steps: StateFlow<List<PlanStepModel>> = tracker.steps
    val isVisible: StateFlow<Boolean> = tracker.isVisible

    private val _isPanelExpanded = MutableStateFlow(true)
    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()
    private val _currentStage = MutableStateFlow(ExecutionStage.IDLE)
    val currentStage: StateFlow<ExecutionStage> = _currentStage.asStateFlow()
    private val _goalDescription = MutableStateFlow("")
    val goalDescription: StateFlow<String> = _goalDescription.asStateFlow()

    private val _executionId = MutableStateFlow("")
    private val _traceFilter = MutableStateFlow(ExecutionTraceFilter.ALL)
    private val _traceAutoScroll = MutableStateFlow(true)
    private val _observedTraceSequence = MutableStateFlow(0L)

    val traceFilter: StateFlow<ExecutionTraceFilter> = _traceFilter.asStateFlow()
    val traceAutoScroll: StateFlow<Boolean> = _traceAutoScroll.asStateFlow()
    val traceEntries: StateFlow<List<ExecutionTraceEvent>> = combine(
        ExecutionStatusBus.trace,
        _executionId,
        _traceFilter,
    ) { events, executionId, filter ->
        if (executionId.isBlank()) emptyList()
        else ExecutionTracePresentation.visibleEntries(events, executionId, filter)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Only expose the panel for a real multi-step execution; Plan Mode can still force it from ChatScreen. */
    val showPanel: StateFlow<Boolean> = combine(steps, ExecutionStatusBus.status, _restoredPlan) { planSteps, state, restored ->
        restored && planSteps.isNotEmpty() || PlanPanelVisibilityPolicy.shouldShow(
            stage = state.executionStage, nodesTotal = state.nodesTotal, stepCount = planSteps.size
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val unreadTraceCount: StateFlow<Int> = combine(
        ExecutionStatusBus.trace,
        _executionId,
        _observedTraceSequence,
    ) { events, executionId, observedThrough ->
        if (executionId.isBlank()) 0
        else ExecutionTracePresentation.unreadCount(events, executionId, observedThrough)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val showTraceJumpToLatest: StateFlow<Boolean> = combine(
        _traceAutoScroll,
        unreadTraceCount,
    ) { autoScroll, unread -> !autoScroll && unread > 0
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        snapshotStore.load()?.takeIf { PlanSnapshotPolicy.canRestore(it) && !PlanSnapshotPolicy.containsSecretMaterial(it) }?.let { saved ->
            tracker.restore(saved.steps, saved.executionId)
            _executionId.value = saved.executionId
            _goalDescription.value = saved.goal
            _currentStage.value = runCatching { ExecutionStage.valueOf(saved.stage) }.getOrDefault(ExecutionStage.IDLE)
            _planContext.value = PlanContext(saved.sessionId, saved.projectId, saved.memoryQuery, saved.skillIds, saved.connectorIds)
            _restoredPlan.value = true
        }
        tracker.start(viewModelScope)
        steps.onEach { currentSteps ->
            if (currentSteps.isNotEmpty()) persistSnapshot(currentSteps)
        }.launchIn(viewModelScope)
        ExecutionStatusBus.status.onEach { state ->
            _currentStage.value = state.executionStage
            if (state.executionId.isNotBlank() && state.executionId != _executionId.value) {
                _executionId.value = state.executionId
                _observedTraceSequence.value = 0L
                _traceAutoScroll.value = true
            }
            if (state.activeGoalDescription.isNotBlank()) _goalDescription.value = state.activeGoalDescription
            if (state.executionStage == ExecutionStage.COMPLETED ||
                state.executionStage == ExecutionStage.FAILED ||
                state.executionStage == ExecutionStage.CANCELLED ||
                (state.executionStage == ExecutionStage.IDLE && !_restoredPlan.value)) {
                viewModelScope.launch {
                    delay(4_000)
                    val cur = ExecutionStatusBus.status.value.executionStage
                    if (cur == ExecutionStage.COMPLETED || cur == ExecutionStage.FAILED ||
                        cur == ExecutionStage.CANCELLED || cur == ExecutionStage.IDLE) {
                        tracker.clear()
                        _restoredPlan.value = false
                        snapshotStore.clear()
                    }
                }
            }
        }.launchIn(viewModelScope)
    }

    fun toggleExpanded() { _isPanelExpanded.value = !_isPanelExpanded.value }
    fun setExpanded(expanded: Boolean) { _isPanelExpanded.value = expanded }
    fun setTraceFilter(filter: ExecutionTraceFilter) { _traceFilter.value = filter }
    fun pauseTraceAutoScroll() { _traceAutoScroll.value = false }
    fun followTraceLatest() { _traceAutoScroll.value = true }
    fun markTraceObserved(sequence: Long) { if (sequence > _observedTraceSequence.value) _observedTraceSequence.value = sequence }
    fun setContext(context: PlanContext) {
        val previous = _planContext.value
        _planContext.value = context.copy(
            projectId = context.projectId.ifBlank { previous.projectId },
            memoryQuery = context.memoryQuery.ifBlank { previous.memoryQuery },
            skillIds = context.skillIds.ifEmpty { previous.skillIds },
            connectorIds = context.connectorIds.ifEmpty { previous.connectorIds },
        )
        persistSnapshot(steps.value)
    }
    fun dismissPanel() { tracker.clear(); _restoredPlan.value = false; snapshotStore.clear() }
    fun collapse() { tracker.clear(); _restoredPlan.value = false; snapshotStore.clear() }

    private fun persistSnapshot(currentSteps: List<PlanStepModel>) {
        if (currentSteps.isEmpty()) return
        val context = _planContext.value
        val snapshot = PlanSnapshot(
            executionId = _executionId.value,
            goal = _goalDescription.value,
            stage = _currentStage.value.name,
            sessionId = context.sessionId,
            projectId = context.projectId,
            memoryQuery = context.memoryQuery.ifBlank { _goalDescription.value },
            skillIds = context.skillIds,
            connectorIds = context.connectorIds,
            steps = currentSteps
        )
        if (!PlanSnapshotPolicy.containsSecretMaterial(snapshot)) snapshotStore.save(snapshot)
    }
}
