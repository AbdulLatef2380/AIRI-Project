package com.airi.assistant.ui.activity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

object AgentActivityBus {
    private const val MAX_SNAPSHOT = 150
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<ActivityEvent>(
        replay = 200, extraBufferCapacity = 500, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ActivityEvent> = _events.asSharedFlow()

    private val snapshotList = CopyOnWriteArrayList<ActivityEvent>()
    private val _recent = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val recentEvents: StateFlow<List<ActivityEvent>> = _recent.asStateFlow()

    init {
        _events.onEach { ev ->
            snapshotList.add(0, ev)
            if (snapshotList.size > MAX_SNAPSHOT) snapshotList.subList(MAX_SNAPSHOT, snapshotList.size).clear()
            _recent.value = snapshotList.toList()
        }.launchIn(scope)
    }

    fun emit(event: ActivityEvent) { scope.launch { _events.emit(event) } }

    fun emit(message: String, category: ActivityCategory, severity: ActivitySeverity = ActivitySeverity.INFO, detail: String? = null) {
        emit(ActivityEvent(message = message, category = category, severity = severity, detail = detail))
    }

    fun clearHistory() { snapshotList.clear(); _recent.value = emptyList() }
}
