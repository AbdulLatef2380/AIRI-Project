package com.airi.assistant.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    init {
        tracker.start(viewModelScope)
        ExecutionStatusBus.status.onEach { state ->
            _currentStage.value = state.executionStage
            if (state.activeGoalDescription.isNotBlank()) _goalDescription.value = state.activeGoalDescription
            if (state.executionStage == ExecutionStage.COMPLETED || state.executionStage == ExecutionStage.FAILED) {
                viewModelScope.launch {
                    delay(4_000)
                    val cur = ExecutionStatusBus.status.value.executionStage
                    if (cur == ExecutionStage.COMPLETED || cur == ExecutionStage.FAILED || cur == ExecutionStage.IDLE) {
                        tracker.clear()
                    }
                }
            }
        }.launchIn(viewModelScope)
    }

    fun toggleExpanded() { _isPanelExpanded.value = !_isPanelExpanded.value }
    fun setExpanded(expanded: Boolean) { _isPanelExpanded.value = expanded }
    fun dismissPanel() { tracker.clear() }
}
