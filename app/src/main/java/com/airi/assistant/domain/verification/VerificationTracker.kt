package com.airi.assistant.domain.verification

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object VerificationTracker {

    private const val TAG = "AIRI_VERIFY"
    private const val MAX_EVENTS = 20

    private val _events = MutableStateFlow<List<VerificationEvent>>(emptyList())
    val events: StateFlow<List<VerificationEvent>> = _events

    fun record(event: VerificationEvent) {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        Log.d(TAG,
            "type=${event.type} queryType=${event.queryType} " +
            "latency=${event.latencyMs}ms tokens=${event.tokens} cut=${event.wasCut}"
        )
    }

    fun lastEvent(): VerificationEvent? = _events.value.lastOrNull()

    fun clear() { _events.value = emptyList() }
}
