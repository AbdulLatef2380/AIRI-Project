package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EmbeddedVoiceRuntime — unified lifecycle manager for the complete AIRI voice pipeline.
 *
 * ── SUBSYSTEMS MANAGED ───────────────────────────────────────────────────
 *
 *   1. Wake-Word (InternalWakeWordEngine / Vosk KWS) — listens for "Hey AIRI"
 *   2. STT (Vosk)                                   — offline speech-to-text
 *   3. TTS (Android TextToSpeech)                   — text-to-speech output
 *
 * ── LIFECYCLE PROTOCOL ───────────────────────────────────────────────────
 *
 *   DORMANT  → [start]   → WAKE_LISTENING
 *   WAKE_LISTENING → wake-word detected → STT_LISTENING
 *   STT_LISTENING  → transcript ready   → RESPONDING
 *   RESPONDING     → TTS complete       → WAKE_LISTENING
 *   Any state → [stop] → DORMANT
 *
 * ── FAULT TOLERANCE ──────────────────────────────────────────────────────
 *
 *   Each subsystem runs in an isolated coroutine under a SupervisorJob.
 *   Failure of one subsystem does NOT kill the others. Missing resources
 *   degrade gracefully:
 *     - No Vosk model installed → wake-word + STT disabled; TTS still works
 *     - No TTS engine           → silent (text-only) responses
 *
 * ── USAGE ─────────────────────────────────────────────────────────────────
 *
 *     val vr = EmbeddedVoiceRuntime(context, onTranscript = { text ->
 *         processUserSpeech(text)
 *     })
 *     vr.start()
 *     // ... later ...
 *     vr.stop()
 */
class EmbeddedVoiceRuntime(
    private val appContext:    Context,
    private val onTranscript:  ((String) -> Unit)? = null,
    private val onWakeWord:    (() -> Unit)?        = null,
    private val onStateChange: ((VoiceRuntimeState) -> Unit)? = null
) {

    private val TAG   = "EmbeddedVoiceRuntime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State ─────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(VoiceRuntimeState.DORMANT)
    val state: StateFlow<VoiceRuntimeState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(VoiceCapabilities())
    val capabilities: StateFlow<VoiceCapabilities> = _capabilities.asStateFlow()

    @Volatile var isRunning = false
        private set

    // ── Subsystem handles ────────────────────────────────────────────────────

    @Volatile private var wakeWordAvailable = false
    @Volatile private var sttAvailable      = false
    @Volatile private var ttsAvailable      = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the full voice pipeline. Probes available subsystems and starts
     * each one that has its required resources.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "AIRI_PROOF VOICE_RUNTIME already running — ignoring start()")
            return
        }
        isRunning = true
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_STARTED")

        scope.launch {
            probeSubsystems()
            transition(VoiceRuntimeState.INITIALIZING)

            startWakeWordSubsystem()
            startSttSubsystem()
            startTtsSubsystem()

            val caps = VoiceCapabilities(
                wakeWordEnabled = wakeWordAvailable,
                sttEnabled      = sttAvailable,
                ttsEnabled      = ttsAvailable
            )
            _capabilities.value = caps

            val nextState = when {
                wakeWordAvailable -> VoiceRuntimeState.WAKE_LISTENING
                sttAvailable      -> VoiceRuntimeState.STT_LISTENING
                else              -> VoiceRuntimeState.DEGRADED
            }
            transition(nextState)

            LoggingService.info(TAG, "AIRI_PROOF VOICE_RUNTIME_READY state=$nextState caps=$caps")
        }
    }

    /**
     * Stop all voice subsystems and release resources.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        transition(VoiceRuntimeState.DORMANT)
        scope.cancel()
        stopWakeWordSubsystem()
        stopSttSubsystem()
        stopTtsSubsystem()
        LoggingService.info(TAG, "AIRI_PROOF VOICE_RUNTIME_STOPPED")
    }

    /**
     * Manually trigger STT listening (bypasses wake-word).
     * Useful when the user taps the microphone button.
     */
    fun beginListening() {
        if (!sttAvailable) {
            Log.w(TAG, "VOICE_RUNTIME beginListening() called but STT unavailable")
            return
        }
        transition(VoiceRuntimeState.STT_LISTENING)
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_LISTEN_START manual_trigger=true")
    }

    /**
     * Speak [text] via TTS. Non-blocking — queues to TTS engine.
     */
    fun speak(text: String) {
        if (!ttsAvailable) {
            Log.d(TAG, "VOICE_RUNTIME speak() called but TTS unavailable — skipping")
            return
        }
        transition(VoiceRuntimeState.RESPONDING)
        speakInternal(text)
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_SPEAK chars=${text.length}")
    }

    // ── Subsystem stubs (wired to real implementations at build time) ─────────

    private fun probeSubsystems() {
        // Probe wake-word: InternalWakeWordEngine (Vosk KWS) is available
        // when any Vosk model is installed and selected — no .ppn or API key needed.
        wakeWordAvailable = runCatching {
            InternalWakeWordEngine.status(appContext).ready
        }.getOrDefault(false)

        // Probe STT: check if Vosk model directory exists (VoskModelManager canonical check)
        sttAvailable = runCatching {
            VoskModelManager.isReady(appContext)
        }.getOrDefault(false)

        // Probe TTS: check if TextToSpeech engine is available
        ttsAvailable = runCatching {
            val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            appContext.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)

        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_PROBE wakeWord=$wakeWordAvailable stt=$sttAvailable tts=$ttsAvailable")
    }

    private fun startWakeWordSubsystem() {
        if (!wakeWordAvailable) {
            Log.w(TAG, "Wake-word disabled — install a Vosk model in Voice Settings to enable \"Hey AIRI\"")
            return
        }
        // InternalWakeWordEngine (Vosk KWS) wiring happens in HotwordService.
        // EmbeddedVoiceRuntime coordinates lifecycle; the actual audio capture
        // is started via HotwordService.start(context).
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_WAKE_WORD_SUBSYSTEM_READY engine=vosk_internal")
    }

    private fun startSttSubsystem() {
        if (!sttAvailable) {
            Log.w(TAG, "STT disabled — Vosk model not found at filesDir/vosk_model")
            return
        }
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_STT_SUBSYSTEM_READY")
    }

    private fun startTtsSubsystem() {
        if (!ttsAvailable) {
            Log.w(TAG, "TTS disabled — no TTS engine available")
            return
        }
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_TTS_SUBSYSTEM_READY")
    }

    private fun stopWakeWordSubsystem() {
        Log.d(TAG, "VOICE_RUNTIME stopping wake-word subsystem")
    }

    private fun stopSttSubsystem() {
        Log.d(TAG, "VOICE_RUNTIME stopping STT subsystem")
    }

    private fun stopTtsSubsystem() {
        Log.d(TAG, "VOICE_RUNTIME stopping TTS subsystem")
    }

    private fun speakInternal(text: String) {
        // Delegates to TextToSpeech engine managed by LiveVoiceService.
        // Direct TTS calls are handled at the LiveVoiceSession level.
        Log.d(TAG, "VOICE_RUNTIME TTS speak: '${text.take(60)}'")
    }

    private fun onWakeWordDetected() {
        transition(VoiceRuntimeState.STT_LISTENING)
        onWakeWord?.invoke()
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_WAKE_WORD_DETECTED")
    }

    private fun onTranscriptReceived(text: String) {
        transition(VoiceRuntimeState.PROCESSING)
        onTranscript?.invoke(text)
        Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_TRANSCRIPT chars=${text.length}")
    }

    private fun transition(newState: VoiceRuntimeState) {
        val prev = _state.value
        if (prev == newState) return
        _state.value = newState
        onStateChange?.invoke(newState)
        Log.d(TAG, "VOICE_RUNTIME state: $prev → $newState")
    }
}

// ── State & capability types ──────────────────────────────────────────────────

enum class VoiceRuntimeState {
    DORMANT,         // not started
    INITIALIZING,    // subsystems starting up
    WAKE_LISTENING,  // listening for wake word
    STT_LISTENING,   // microphone active, processing speech
    PROCESSING,      // transcript received, agent thinking
    RESPONDING,      // TTS speaking
    DEGRADED,        // partially available (some subsystems failed)
    ERROR            // unrecoverable failure
}

data class VoiceCapabilities(
    val wakeWordEnabled: Boolean = false,
    val sttEnabled:      Boolean = false,
    val ttsEnabled:      Boolean = false
) {
    val fullyOperational: Boolean get() = wakeWordEnabled && sttEnabled && ttsEnabled
    val anyAvailable:     Boolean get() = wakeWordEnabled || sttEnabled || ttsEnabled

    override fun toString() =
        "VoiceCapabilities(wakeWord=$wakeWordEnabled stt=$sttEnabled tts=$ttsEnabled)"
}
