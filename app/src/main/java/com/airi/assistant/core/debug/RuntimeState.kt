package com.airi.assistant.core.debug

import kotlinx.coroutines.flow.MutableStateFlow

data class RuntimeState(
    val lastQueryType: String  = "UNKNOWN",
    val firstTokenMs: Long     = 0L,
    val totalLatencyMs: Long   = 0L,
    val p50LatencyMs: Long     = -1L,
    val p90LatencyMs: Long     = -1L,
    val tokensPerSecond: Float = 0f,
    val fastPath: Boolean      = false,
    val wasCut: Boolean        = false,
    val voiceState: String     = "IDLE"
)

object RuntimeStore {
    val state = MutableStateFlow(RuntimeState())

    fun update(transform: RuntimeState.() -> RuntimeState) {
        state.value = state.value.transform()
    }
}
