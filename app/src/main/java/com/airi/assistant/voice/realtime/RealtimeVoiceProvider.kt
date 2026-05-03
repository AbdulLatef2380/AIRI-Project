package com.airi.assistant.voice.realtime

import kotlinx.coroutines.flow.Flow

/**
 * Cloud realtime voice transport abstraction.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * IMPLEMENTATIONS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   GeminiLiveProvider    — Gemini Live Multimodal API (bidirectional WebSocket)
 *   OpenAIRealtimeProvider — OpenAI Realtime API (bidirectional WebSocket)
 *
 * Both providers expose the same contract so [LiveVoiceSession] / the
 * ChatViewModel can swap between local (Vosk + Android TTS) and cloud
 * without restructuring any orchestration logic.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * AUDIO FORMAT
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Input  (mic → server): PCM-16 at 16 kHz mono
 *   Output (server → speaker): PCM-16 at 24 kHz mono (both APIs)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * BARGE-IN
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   When the local VAD detects user speech during server audio playback,
 *   call [interrupt] to cancel the server-side generation. The server will
 *   stop sending [audioResponseFlow] chunks. The caller stops local
 *   AudioTrack playback independently (as with local TTS).
 */
interface RealtimeVoiceProvider {

    /** Human-readable identifier for logs and observability. */
    val name: String

    /** API endpoint or WebSocket URL — implementation-defined. */
    val endpointDescription: String

    /**
     * Whether this provider supports true bidirectional streaming
     * (audio in AND audio out simultaneously on the same transport).
     * If false, the provider uses a turn-based request/response model.
     */
    val supportsBidirectionalStreaming: Boolean

    /**
     * Estimated round-trip latency class for UX calibration.
     */
    val expectedLatencyMs: IntRange

    // ─────────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Establish the realtime session.
     *
     * Must complete before [sendAudioChunk] or any flow collection.
     * Returns [ConnectResult.Success] or [ConnectResult.Failure].
     */
    suspend fun connect(
        systemPrompt: String,
        apiKey:       String,
        voiceId:      String = "alloy"
    ): ConnectResult

    /**
     * Tear down the session. Idempotent — safe to call multiple times
     * or after [connect] failure.
     */
    suspend fun disconnect()

    // ─────────────────────────────────────────────────────────────────────
    // Audio I/O
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Send a raw PCM-16 audio chunk from the microphone.
     *
     * The provider buffers and encodes (Base64 / Opus) before transmitting.
     * Frame size: 128–1024 samples depending on provider VAD requirements.
     * Non-blocking — the coroutine queues the chunk on the send channel.
     */
    suspend fun sendAudioChunk(pcm16: ShortArray)

    /**
     * Signal end of user audio input (commit the turn).
     *
     * Required by some providers (e.g. OpenAI Realtime) to trigger server
     * VAD processing. Gemini Live handles turn detection server-side
     * automatically; call is a no-op for those implementations.
     */
    suspend fun commitAudioTurn()

    /**
     * Hot [Flow] of server-sent audio response chunks (PCM-16, 24 kHz).
     *
     * Collect in the ViewModel / Service and route to an AudioTrack for
     * real-time playback. Complete when the server closes the audio turn.
     */
    val audioResponseFlow: Flow<ShortArray>

    /**
     * Hot [Flow] of incremental transcript text from the server.
     *
     * Route to [LiveVoiceSession.onPartialTranscript] for live display.
     * Complete when the server finalizes the transcript.
     */
    val transcriptFlow: Flow<String>

    /**
     * Hot [Flow] of finalized AI text responses.
     *
     * Use for chat log display and memory recording.
     * Each emission is a complete sentence or the full turn response,
     * depending on provider chunking behavior.
     */
    val responseTextFlow: Flow<String>

    // ─────────────────────────────────────────────────────────────────────
    // Barge-in
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Interrupt the server's current audio response.
     *
     * Call immediately when local full-duplex VAD fires. The server stops
     * generating. [audioResponseFlow] will complete shortly after.
     * The caller is responsible for stopping local AudioTrack playback.
     */
    suspend fun interrupt()

    // ─────────────────────────────────────────────────────────────────────
    // Health
    // ─────────────────────────────────────────────────────────────────────

    /** True if the WebSocket / transport is currently connected. */
    val isConnected: Boolean

    // ─────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────

    sealed class ConnectResult {
        object Success : ConnectResult()
        data class Failure(val reason: String, val code: Int = -1) : ConnectResult()
    }
}

/**
 * Null-object implementation — local pipeline (Vosk + Android TTS).
 *
 * Used as the default [RealtimeVoiceProvider] when no cloud key is set.
 * All methods are no-ops; flows never emit. The local VoiceManager
 * handles audio directly.
 */
object LocalVoicePipeline : RealtimeVoiceProvider {
    override val name                        = "local"
    override val endpointDescription         = "Vosk STT + Android TTS (on-device)"
    override val supportsBidirectionalStreaming = false
    override val expectedLatencyMs           = 80..250
    override val isConnected                 = true

    override suspend fun connect(systemPrompt: String, apiKey: String, voiceId: String) =
        RealtimeVoiceProvider.ConnectResult.Success

    override suspend fun disconnect()                 {}
    override suspend fun sendAudioChunk(pcm16: ShortArray) {}
    override suspend fun commitAudioTurn()            {}
    override suspend fun interrupt()                  {}

    override val audioResponseFlow: Flow<ShortArray>  = kotlinx.coroutines.flow.emptyFlow()
    override val transcriptFlow:    Flow<String>      = kotlinx.coroutines.flow.emptyFlow()
    override val responseTextFlow:  Flow<String>      = kotlinx.coroutines.flow.emptyFlow()
}
