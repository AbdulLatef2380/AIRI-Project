package com.airi.assistant.ui.activity

import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.runtime.profiler.FlowPressureMonitor
import com.airi.assistant.security.CommandRedactor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

object AgentActivityBus {
    private const val MAX_SNAPSHOT = 150
    private val lock = Any()
    private var nextSequenceNumber = 0L

    // Replay reduced from 200 → 50.
    // Historical events live in snapshotList (150 cap) which collectors read
    // via recentEvents StateFlow. replay=200 was holding 200 ActivityEvent
    // objects in the SharedFlow cache permanently — growing heap in long
    // sessions. No subscriber needs >50 events on re-subscription since
    // UI consumers read from recentEvents StateFlow directly.
    private val _events = MutableSharedFlow<ActivityEvent>(
        replay = 50, extraBufferCapacity = 500, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ActivityEvent> = _events.asSharedFlow()

    private val snapshotList = ArrayDeque<ActivityEvent>()
    private val _recent = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val recentEvents: StateFlow<List<ActivityEvent>> = _recent.asStateFlow()

    init {
        // : Register with FlowPressureMonitor for replay-cache backpressure auditing.
        // Slow collectors on AgentActivityBus events are now logged to AuditRepository
        // and surfaced in the DeveloperCenter Profiler tab ().
        FlowPressureMonitor.auditSharedFlow("AgentActivityBus", _events)
    }

    fun emit(event: ActivityEvent) {
        val safeEvent = sanitizeActivityEventForDisplay(event)
        // Ownership is explicit. Never attach an unscoped background event to
        // whichever execution happens to be active when it is emitted; that can
        // leak stale events into a different chat turn.
        synchronized(lock) {
            val orderedEvent = safeEvent.copy(sequenceNumber = ++nextSequenceNumber)
            snapshotList.addFirst(orderedEvent)
            while (snapshotList.size > MAX_SNAPSHOT) snapshotList.removeLast()
            _recent.value = snapshotList.toList()
            // Feed emit counter before the actual emit. DROP_OLDEST keeps the
            // bounded SharedFlow non-blocking for runtime producers.
            runCatching {
                com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.recordBusEmit()
            }
            _events.tryEmit(orderedEvent)
        }
    }

    fun emit(
        message: String,
        category: ActivityCategory,
        severity: ActivitySeverity = ActivitySeverity.INFO,
        detail: String? = null,
        machineTag: String? = null,
        executionId: String? = null,
    ) {
        emit(
            ActivityEvent(
                message = message,
                category = category,
                severity = severity,
                detail = detail,
                machineTag = machineTag,
                executionId = executionId?.takeIf(String::isNotBlank),
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearHistory() {
        synchronized(lock) {
            snapshotList.clear()
            _recent.value = emptyList()
            _events.resetReplayCache()
        }
    }
}

/**
 * Redacts sensitive values before an activity event is retained or sent to UI collectors.
 * CommandRedactor handles credential-shaped shell arguments; PrivacyGuard adds trace-specific
 * protection for credentials, private paths/URIs, device identifiers, and related context.
 */
internal fun sanitizeActivityEventForDisplay(event: ActivityEvent): ActivityEvent {
    fun sanitize(value: String): String = PrivacyGuard.redactForTrace(
        CommandRedactor.redact(value),
        maximumChars = 1_000,
    )

    return event.copy(
        message = sanitize(event.message),
        detail = event.detail?.let(::sanitize),
    )
}
