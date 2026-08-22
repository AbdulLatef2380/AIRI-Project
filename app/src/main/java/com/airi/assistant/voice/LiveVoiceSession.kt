package com.airi.assistant.voice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle-independent voice session state holder.
 *
 * Owned by [LiveVoiceService] (application-scoped foreground service).
 * The UI binds to the service via [LiveVoiceService.LocalBinder] and
 * observes the [StateFlow] fields — no Activity references held here.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 * ─────────────────────────────────────────────────────────────────────────
 *
 * [transitionTo] is the only mutating entry point for [state].
 * MutableStateFlow updates are always atomic.
 * [sessionIdCounter] uses AtomicLong for lock-free increments.
 * All other mutable fields use @Volatile for cross-thread visibility.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ROTATION SURVIVAL
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Because this class is owned by the foreground service, all StateFlow
 * collectors automatically receive the latest value on resubscription after
 * an Activity recreate — no explicit save/restore needed.
 */
class LiveVoiceSession(
    private val logger: (level: String, message: String) -> Unit = { level, message ->
        when (level) {
            "D" -> Log.d(TAG, message)
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.e(TAG, message)
        }
    }
) {

    // ── Session identity ──────────────────────────────────────────────────────

    private val sessionIdCounter = AtomicLong(0L)

    /** Monotonically increasing session ID. 0 = no active session. */
    @Volatile var currentSessionId: Long = 0L
        private set

    // ── Pipeline state ────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(VoicePipelineState.IDLE)
    val state: StateFlow<VoicePipelineState> = _state.asStateFlow()

    // ── Streaming transcript ──────────────────────────────────────────────────

    private val _partialTranscript = MutableStateFlow("")
    /** Partial STT result during LISTENING state. Cleared on turn complete. */
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    // ── Fallback transcript bus ────────────────────────────────────────────────

    private val _pendingTranscript = MutableStateFlow<String?>(null)
    /**
     * Set by [LiveVoiceService] when [VoiceAgentRouter] returns Fallback —
     * meaning no sub-agent matched and the transcript should be routed to the
     * LLM / AgentService pipeline.
     *
     * Clients bound to [LiveVoiceService.LocalBinder] observe this flow.
     * Consumer MUST call [clearPendingTranscript] after handling to reset.
     *
     * This StateFlow is a direct-binding alternative to
     * [com.airi.assistant.core.ServiceLocator.voiceTranscriptBus]. Both carry
     * the same transcript; ChatViewModel uses the bus (no service binding needed).
     */
    val pendingTranscript: StateFlow<String?> = _pendingTranscript.asStateFlow()

    /** Emit a transcript that no agent claimed. See [pendingTranscript]. */
    fun emitPendingTranscript(text: String) {
        _pendingTranscript.value = text
        logger("D", "AIRI VOICE_PENDING_TRANSCRIPT chars=${text.length}")
    }

    /** Clear after the consumer (ChatViewModel / bound client) has handled it. */
    fun clearPendingTranscript() {
        _pendingTranscript.value = null
    }

    // ── Latency telemetry ─────────────────────────────────────────────────────

    private val _latency = MutableStateFlow(LatencySnapshot())
    /** Latest latency measurements — updated on each turn. */
    val latency: StateFlow<LatencySnapshot> = _latency.asStateFlow()

    // ── Session metrics ───────────────────────────────────────────────────────

    private val _metrics = MutableStateFlow(SessionMetrics())
    /** Cumulative session metrics — interruptions, errors, turns. */
    val metrics: StateFlow<SessionMetrics> = _metrics.asStateFlow()

    // ── Recovery state ────────────────────────────────────────────────────────

    @Volatile var recoveryAttempts: Int = 0
        private set

    companion object {
        private const val TAG = "LiveVoiceSession"
        const val MAX_RECOVERY_ATTEMPTS = 3
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Begin a new voice session. Returns the new session ID.
     * Resets all per-session counters.
     */
    fun beginSession(): Long {
        val id = sessionIdCounter.incrementAndGet()
        currentSessionId = id
        recoveryAttempts = 0
        _partialTranscript.value = ""
        _metrics.value = SessionMetrics()
        _latency.value = LatencySnapshot()
        transitionTo(VoicePipelineState.LISTENING)
        logger("I", "AIRI VOICE_SESSION_BEGIN id=$id")
        return id
    }

    /** End the current session. Returns to IDLE and clears partial transcript. */
    fun endSession() {
        _partialTranscript.value = ""
        recoveryAttempts = 0
        transitionTo(VoicePipelineState.IDLE)
        logger("I", "AIRI VOICE_SESSION_END id=$currentSessionId " +
                "turns=${_metrics.value.completedTurns} " +
                "interruptions=${_metrics.value.interruptionCount}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State transitions — called by LiveVoiceService from VoiceListener callbacks
    // ─────────────────────────────────────────────────────────────────────────

    /** Call when wake-word fires or user manually triggers listen. */
    fun onListenStart() {
        transitionTo(VoicePipelineState.LISTENING)
    }

    /** Call on each STT partial result. */
    fun onPartialTranscript(text: String) {
        _partialTranscript.value = text
    }

    /**
     * Call when STT emits the final result for this turn.
     * Clears partial transcript (replaced by the confirmed final text).
     */
    fun onSpeechResult(text: String) {
        _partialTranscript.value = text
        transitionTo(VoicePipelineState.THINKING)
    }

    /**
     * Call when the first LLM token arrives and TTS begins.
     * [sttToFirstTokenMs] = time from STT result to first audio byte.
     */
    fun onResponseStreaming(sttToFirstTokenMs: Long) {
        _latency.value = _latency.value.copy(lastSttToFirstTokenMs = sttToFirstTokenMs)
        transitionTo(VoicePipelineState.STREAMING_RESPONSE)
    }

    /**
     * Call when TTS completes naturally (no barge-in).
     * Auto-increments turn counter.
     */
    fun onTurnComplete() {
        _partialTranscript.value = ""
        _metrics.value = _metrics.value.copy(
            completedTurns = _metrics.value.completedTurns + 1
        )
        transitionTo(VoicePipelineState.IDLE)
        logger("D", "AIRI VOICE_TURN_COMPLETE total=${_metrics.value.completedTurns}")
    }

    /**
     * Call when full-duplex VAD fires during TTS playback (barge-in).
     * At call time, TTS has ALREADY been stopped and VAD mic ALREADY released
     * (enforced by VoiceManager before firing onVadInterrupted).
     */
    fun onBargeIn() {
        _metrics.value = _metrics.value.copy(
            interruptionCount = _metrics.value.interruptionCount + 1
        )
        transitionTo(VoicePipelineState.INTERRUPTED)
        logger("D", "AIRI VOICE_BARGE_IN count=${_metrics.value.interruptionCount}")
    }

    /** Call after barge-in cleanup is complete and STT is re-armed. */
    fun onResumeListening() = transitionTo(VoicePipelineState.LISTENING)

    /**
     * Call on any pipeline transport / STT / TTS error.
     * Returns true if recovery should be attempted (retry budget remaining).
     * Returns false when max retries exhausted — caller should transition to IDLE.
     */
    fun onError(reason: String): Boolean {
        _metrics.value = _metrics.value.copy(
            errorCount = _metrics.value.errorCount + 1
        )
        return if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            recoveryAttempts++
            transitionTo(VoicePipelineState.RECOVERING)
            logger("W", "VOICE_RECOVERING attempt=$recoveryAttempts reason=$reason")
            true
        } else {
            logger("E", "VOICE_MAX_RETRIES_EXHAUSTED reason=$reason")
            endSession()
            false
        }
    }

    /** Call when a recovery attempt succeeds. Resets retry counter. */
    fun onRecoverySuccess() {
        recoveryAttempts = 0
        transitionTo(VoicePipelineState.LISTENING)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Latency recording — called from service with wall-clock measurements
    // ─────────────────────────────────────────────────────────────────────────

    fun recordSttLatency(ms: Long) {
        _latency.value = _latency.value.copy(lastSttLatencyMs = ms)
    }

    fun recordTtsFirstByteLatency(ms: Long) {
        _latency.value = _latency.value.copy(lastTtsFirstByteMs = ms)
    }

    fun recordInterruptionLatency(ms: Long) {
        _latency.value = _latency.value.copy(lastInterruptionMs = ms)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun transitionTo(next: VoicePipelineState) {
        val previous = _state.value
        if (previous == next) return
        if (!isValidTransition(previous, next)) {
            logger("W", "AIRI VOICE_INVALID_TRANSITION $previous → $next session=$currentSessionId")
            return
        }
        _state.value = next
        logger("D", "AIRI VOICE_STATE $previous → $next session=$currentSessionId")
    }

    private fun isValidTransition(
        previous: VoicePipelineState,
        next: VoicePipelineState
    ): Boolean {
        if (next == VoicePipelineState.IDLE || next == VoicePipelineState.RECOVERING) return true
        return when (previous) {
            VoicePipelineState.IDLE -> next == VoicePipelineState.LISTENING
            VoicePipelineState.LISTENING -> next == VoicePipelineState.THINKING
            VoicePipelineState.THINKING -> next == VoicePipelineState.STREAMING_RESPONSE
            VoicePipelineState.STREAMING_RESPONSE -> next == VoicePipelineState.INTERRUPTED
            VoicePipelineState.INTERRUPTED -> next == VoicePipelineState.LISTENING
            VoicePipelineState.RECOVERING -> next == VoicePipelineState.LISTENING
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data models
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Latency snapshot updated after each voice turn.
     * All values in milliseconds.
     */
    data class LatencySnapshot(
        /** Time from end of user speech to STT final result. */
        val lastSttLatencyMs:     Long = 0L,
        /** Time from STT result to first LLM token (inference + TTFB). */
        val lastSttToFirstTokenMs: Long = 0L,
        /** Time from LLM first token to first TTS audio byte. */
        val lastTtsFirstByteMs:   Long = 0L,
        /** Time from VAD detection to TTS stop + mic release. */
        val lastInterruptionMs:   Long = 0L
    ) {
        /** Perceived end-to-end latency: speech end → first audio response byte. */
        val perceivedLatencyMs: Long
            get() = lastSttLatencyMs + lastSttToFirstTokenMs + lastTtsFirstByteMs
    }

    /** Cumulative metrics for the current session. */
    data class SessionMetrics(
        val completedTurns:    Int = 0,
        val interruptionCount: Int = 0,
        val errorCount:        Int = 0,
        val recoveryCount:     Int = 0
    )
}
