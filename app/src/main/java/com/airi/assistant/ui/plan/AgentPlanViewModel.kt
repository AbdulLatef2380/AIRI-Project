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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentPlanViewModel(application: Application) : AndroidViewModel(application) {

    private val tracker = TaskExecutionTracker()

    val steps: StateFlow<List<PlanStepModel>> = tracker.steps
    val isVisible: StateFlow<Boolean>          = tracker.isVisible

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

    /** Chronological entries for the active execution only, filtered by UI selection. */
    val traceEntries: StateFlow<List<ExecutionTraceEvent>> = combine(
        ExecutionStatusBus.trace,
        _executionId,
        _traceFilter,
    ) { events, executionId, filter ->
        if (executionId.isBlank()) emptyList()
        else ExecutionTracePresentation.visibleEntries(events, executionId, filter)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Show the bottom sheet as soon as either graph steps or an owned trace entry
     * is available. Direct tool loops can have no predeclared graph nodes.
     */
    val showPanel: StateFlow<Boolean> = combine(steps, traceEntries) { planSteps, trace ->
        planSteps.isNotEmpty() || trace.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Entries appended while the user has paused automatic following. */
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
        tracker.start(viewModelScope)
        ExecutionStatusBus.status.onEach { state ->
            _currentStage.value = state.executionStage
            if (state.executionId != _executionId.value) {
                _executionId.value = state.executionId
                _observedTraceSequence.value = 0L
                _traceAutoScroll.value = true
            }
            if (state.activeGoalDescription.isNotBlank()) _goalDescription.value = state.activeGoalDescription
            // : Auto-collapse panel when execution finishes (COMPLETED/FAILED/IDLE)
            if (state.executionStage == ExecutionStage.COMPLETED ||
                state.executionStage == ExecutionStage.FAILED ||
                state.executionStage == ExecutionStage.CANCELLED ||
                state.executionStage == ExecutionStage.IDLE) {
                viewModelScope.launch {
                    delay(4_000)
                    val cur = ExecutionStatusBus.status.value.executionStage
                    if (cur == ExecutionStage.COMPLETED ||
                        cur == ExecutionStage.FAILED ||
                        cur == ExecutionStage.CANCELLED ||
                        cur == ExecutionStage.IDLE) {
                        tracker.clear()
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
    fun markTraceObserved(sequence: Long) {
        if (sequence > _observedTraceSequence.value) _observedTraceSequence.value = sequence
    }
    fun dismissPanel() { tracker.clear() }
    /** : Called by ModalBottomSheet onDismissRequest. */
    fun collapse() { tracker.clear() }
}
