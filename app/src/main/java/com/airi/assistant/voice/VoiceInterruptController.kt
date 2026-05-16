package com.airi.assistant.voice

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInterruptController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val TAG = "VoiceInterruptController"

    private val _isInterrupting = MutableStateFlow(false)
    val isInterrupting: StateFlow<Boolean> = _isInterrupting.asStateFlow()

    private val interruptInFlight = AtomicBoolean(false)
    private var interruptJob: Job? = null

    var onStopTts:         (() -> Unit)? = null
    var onCancelGeneration:(() -> Unit)? = null
    var onRearmStt:        (() -> Unit)? = null
    var onTransitionTo:    ((VoicePipelineState) -> Unit)? = null

    fun onVadSpeechDetected() {
        if (!interruptInFlight.compareAndSet(false, true)) return
        interruptJob?.cancel()
        interruptJob = scope.launch {
            Log.i(TAG, "BARGE-IN detected — stopping AIRI output")
            _isInterrupting.value = true
            AgentActivityBus.emit("Barge-in detected — stopping output", ActivityCategory.VOICE)
            try {
                onStopTts?.invoke()
                onCancelGeneration?.invoke()
                delay(180L)
                onTransitionTo?.invoke(VoicePipelineState.LISTENING)
                onRearmStt?.invoke()
                AgentActivityBus.emit("Re-armed after barge-in", ActivityCategory.VOICE)
            } finally {
                _isInterrupting.value = false
                interruptInFlight.set(false)
            }
        }
    }

    fun suppressNextInterrupt() {
        interruptInFlight.set(true)
        scope.launch { delay(2_000L); interruptInFlight.set(false) }
    }

    fun release() {
        interruptJob?.cancel()
        _isInterrupting.value = false
        interruptInFlight.set(false)
    }
}
