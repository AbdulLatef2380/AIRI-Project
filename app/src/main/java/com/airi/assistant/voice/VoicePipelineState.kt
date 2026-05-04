package com.airi.assistant.voice

/**
 * Formal state machine for the AIRI full-duplex voice pipeline.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * VALID TRANSITIONS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   IDLE               → LISTENING          wake-word / manual trigger
 *   LISTENING          → THINKING           STT final result received
 *   LISTENING          → IDLE               manual stop / timeout
 *   THINKING           → STREAMING_RESPONSE first LLM token + TTS start
 *   THINKING           → IDLE               LLM error / cancellation
 *   STREAMING_RESPONSE → IDLE               natural end-of-turn (TTS done)
 *   STREAMING_RESPONSE → INTERRUPTED        barge-in VAD confirmed
 *   INTERRUPTED        → LISTENING          STT re-armed after barge-in
 *   INTERRUPTED        → IDLE               user stops after barge-in
 *   * (any)            → RECOVERING         transport error with retry budget
 *   RECOVERING         → LISTENING          retry succeeded
 *   RECOVERING         → IDLE               retry budget exhausted
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ILLEGAL OVERLAPS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   LISTENING + STREAMING_RESPONSE — mic and TTS cannot both be active
 *   (enforced by VoiceManager via audio-source exclusivity)
 *
 * Enforced at [LiveVoiceSession] via [LiveVoiceSession.transitionTo].
 */
enum class VoicePipelineState {

    /**
     * No audio activity. Wake-word listener may be armed separately via
     * [VoiceManager.startWakeWordDetection].
     */
    IDLE,

    /**
     * Microphone open (VOICE_RECOGNITION source), Vosk/platform STT streaming.
     * Partial transcripts flowing to [LiveVoiceSession.partialTranscript].
     */
    LISTENING,

    /**
     * STT final result received; LLM inference in progress.
     * No audio hardware open. UI shows a "thinking" indicator.
     */
    THINKING,

    /**
     * LLM tokens flowing; TTS speaking (VOICE_COMMUNICATION / speaker).
     * Full-duplex VAD armed for barge-in detection.
     */
    STREAMING_RESPONSE,

    /**
     * User interrupted TTS playback via barge-in VAD.
     * TTS already stopped; VAD mic released; STT about to re-arm.
     * Transition to [LISTENING] once STT re-arm is complete.
     */
    INTERRUPTED,

    /**
     * Transport or pipeline error; executing retry / fallback.
     * UI should show a subtle "reconnecting" indicator.
     * Transitions to [LISTENING] on success or [IDLE] on budget exhaustion.
     */
    RECOVERING;

    // ── Computed properties for concise UI / logic checks ──────────────────

    /** True when the microphone is (or is about to be) open for input. */
    val isAudioInputActive: Boolean
        get() = this == LISTENING || this == INTERRUPTED

    /** True when TTS is speaking and barge-in VAD is armed. */
    val isTtsSpeaking: Boolean
        get() = this == STREAMING_RESPONSE

    /** True when waiting for an LLM or network response. */
    val isProcessing: Boolean
        get() = this == THINKING || this == RECOVERING

    /** True when any audio hardware is or will be in use. */
    val isAudioActive: Boolean
        get() = isAudioInputActive || isTtsSpeaking

    /** True when the voice session is quiescent — safe to release all audio. */
    val isQuiescent: Boolean
        get() = this == IDLE
}
