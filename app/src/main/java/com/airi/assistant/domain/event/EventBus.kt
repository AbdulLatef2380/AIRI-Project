package com.airi.assistant.domain.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object EventBus {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<AppEvent>(
        replay              = 50,
        extraBufferCapacity = 200,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun emit(event: AppEvent) {
        scope.launch { _events.emit(event) }
    }

    fun emitSync(event: AppEvent) {
        _events.tryEmit(event)
    }
}
