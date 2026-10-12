package com.airi.assistant.ui.activity

import com.airi.assistant.core.ExecutionStatusBus
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
        // Capture ownership before queueing the asynchronous emission. Reading
        // ExecutionStatusBus.status inside the launched coroutine can attribute
        // an event produced by execution A to a newer execution B.
        val activeExecutionId = ExecutionStatusBus.status.value.executionId
            .takeIf { it.isNotBlank() }
        scope.launch {
            // Runtime services often emit through this shared bus without a UI
            // dependency. Attribute those events to the active execution here,
            // so the task panel can count terminal, sandbox, connector, and
            // artifact work without each producer inventing its own linkage.
            val ownedEvent = attachExecutionId(event, activeExecutionId)
            // Feed emit counter before the actual emit. The monitor sees emit
            // pressure even when the buffer is full and the event is dropped.
            runCatching {
                com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.recordBusEmit()
            }
            _events.emit(ownedEvent)
        }
    }

    /**
     * Pure ownership projection used by the emission path and JVM tests.
     * Explicit event ownership always wins; otherwise the caller's synchronous
     * execution snapshot is used. A later global status must not rewrite it.
     */
    internal fun attachExecutionId(event: ActivityEvent, activeExecutionId: String?): ActivityEvent =
        if (event.executionId.isNullOrBlank()) event.copy(executionId = activeExecutionId?.takeIf { it.isNotBlank() }) else event

    fun emit(
        message: String,
        category: ActivityCategory,
        severity: ActivitySeverity = ActivitySeverity.INFO,
        detail: String? = null,
        machineTag: String? = null
    ) {
        emit(ActivityEvent(message = message, category = category, severity = severity, detail = detail, machineTag = machineTag))
    }

    fun clearHistory() { snapshotList.clear(); _recent.value = emptyList() }
}
