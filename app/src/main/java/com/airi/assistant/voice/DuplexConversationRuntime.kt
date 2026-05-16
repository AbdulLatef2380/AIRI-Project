package com.airi.assistant.voice

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DuplexConversationRuntime — full-duplex voice turn coordinator.
 *
 * Bridges: STT transcript → LLM stream → IncrementalTtsEngine → barge-in via VoiceInterruptController
 *
 * State machine mirrors [VoicePipelineState] but is self-contained for the
 * runtime's perspective. Ownership of [VoicePipelineState] remains in
 * [LiveVoiceSession]; this runtime observes and drives via callbacks.
 */
class DuplexConversationRuntime(
    private val interruptController: VoiceInterruptController,
    private val ttsEngine:           IncrementalTtsEngine,
    private val onTranscript:        suspend (String) -> Unit,
    private val onRequestLlmStream:  suspend (String, onToken: (String) -> Unit) -> Unit
) {
    private val TAG = "DuplexConversationRuntime"

    enum class ConversationState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED, ERROR }

    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val runtimeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var turnJob: Job? = null

    fun start() {
        wireInterruptController()
        _conversationState.value = ConversationState.LISTENING
        AgentActivityBus.emit("Duplex voice runtime started", ActivityCategory.VOICE)
        Log.i(TAG, "DuplexConversationRuntime started")
    }

    fun stop() {
        turnJob?.cancel()
        ttsEngine.stop()
        interruptController.release()
        _conversationState.value = ConversationState.IDLE
        runtimeScope.cancel()
        AgentActivityBus.emit("Duplex voice runtime stopped", ActivityCategory.VOICE)
    }

    /** Entry point from STT layer — call with each final transcript. */
    fun onFinalTranscript(text: String) {
        if (text.isBlank()) return
        if (_conversationState.value == ConversationState.THINKING || _conversationState.value == ConversationState.SPEAKING) {
            interruptController.onVadSpeechDetected()
        }
        turnJob?.cancel()
        turnJob = runtimeScope.launch { executeTurn(text) }
    }

    private suspend fun executeTurn(transcript: String) {
        Log.i(TAG, "TURN START: '${transcript.take(80)}'")
        AgentActivityBus.emit("Processing: ${transcript.take(60)}", ActivityCategory.VOICE)
        try {
            _conversationState.value = ConversationState.THINKING
            onTranscript(transcript)
            _conversationState.value = ConversationState.SPEAKING
            ttsEngine.beginStream()
            onRequestLlmStream(transcript) { chunk -> ttsEngine.onToken(chunk) }
            ttsEngine.endStream()
            _conversationState.value = ConversationState.LISTENING
            Log.i(TAG, "TURN COMPLETE — re-armed")
        } catch (e: Exception) {
            Log.w(TAG, "Turn error: ${e.message}")
            _conversationState.value = ConversationState.ERROR
            ttsEngine.stop()
            AgentActivityBus.emit("Voice turn error: ${e.message?.take(60)}", ActivityCategory.VOICE)
            delay(1_000)
            _conversationState.value = ConversationState.LISTENING
        }
    }

    private fun wireInterruptController() {
        interruptController.onStopTts         = { ttsEngine.stop() }
        interruptController.onCancelGeneration = { turnJob?.cancel() }
        interruptController.onRearmStt        = { _conversationState.value = ConversationState.LISTENING }
        interruptController.onTransitionTo    = { state ->
            _conversationState.value = when (state) {
                VoicePipelineState.LISTENING -> ConversationState.LISTENING
                VoicePipelineState.IDLE      -> ConversationState.IDLE
                else                         -> ConversationState.INTERRUPTED
            }
        }
    }
}
