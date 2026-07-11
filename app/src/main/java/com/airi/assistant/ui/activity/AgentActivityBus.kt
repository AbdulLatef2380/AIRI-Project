package com.airi.assistant.ui.activity

import com.airi.assistant.runtime.profiler.FlowPressureMonitor
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

    // Replay reduced from 200 → 50.
    // Historical events live in snapshotList (150 cap) which collectors read
    // via recentEvents StateFlow. replay=200 was holding 200 ActivityEvent
    // objects in the SharedFlow cache permanently — growing heap in long
    // sessions. No subscriber needs >50 events on re-subscription since
    // UI consumers read from recentEvents StateFlow directly.
    private val _events = MutableSharedFlow<ActivityEvent>(
        replay = 50, extraBufferCapacity = 500, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ActivityEvent> = _events.asSharedFlow()

    private val snapshotList = CopyOnWriteArrayList<ActivityEvent>()
    private val _recent = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val recentEvents: StateFlow<List<ActivityEvent>> = _recent.asStateFlow()

    init {
        // : Register with FlowPressureMonitor for replay-cache backpressure auditing.
        // Slow collectors on AgentActivityBus events are now logged to AuditRepository
        // and surfaced in the DeveloperCenter Profiler tab ().
        FlowPressureMonitor.auditSharedFlow("AgentActivityBus", _events)

        _events.onEach { ev ->
            snapshotList.add(0, ev)
            if (snapshotList.size > MAX_SNAPSHOT) snapshotList.subList(MAX_SNAPSHOT, snapshotList.size).clear()
            _recent.value = snapshotList.toList()
            // Feed drain counter so RuntimeHealthMonitor can detect saturation.
            runCatching {
                com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.recordBusDrain()
            }
        }.launchIn(scope)
    }

    fun emit(event: ActivityEvent) {
        scope.launch {
            // Feed emit counter before the actual emit. The monitor sees emit
            // pressure even when the buffer is full and the event is dropped.
            runCatching {
                com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.recordBusEmit()
            }
            _events.emit(event)
        }
    }

    fun emit(message: String, category: ActivityCategory, severity: ActivitySeverity = ActivitySeverity.INFO, detail: String? = null) {
        emit(ActivityEvent(message = message, category = category, severity = severity, detail = detail))
    }

    fun clearHistory() { snapshotList.clear(); _recent.value = emptyList() }
}
