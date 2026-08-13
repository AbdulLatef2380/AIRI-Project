package com.airi.assistant.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.airi.assistant.voice.FullDuplexVadEngine
import com.airi.assistant.voice.HotwordService
import com.airi.assistant.voice.VoskEngine
import com.airi.assistant.voice.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vosk.Model
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * VoiceManager — single owner of all real-time audio for AIRI.
 *
 * Subsystems:
 *   1. TextToSpeech        — platform TTS, no network.
 *   2. Wake-word           — Porcupine via HotwordService.
 *   3. Speech-to-text      — VoskEngine (on-device) or platform SpeechRecognizer.
 *   4. Full-duplex VAD     — FullDuplexVadEngine (Silero ONNX on-device).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * FULL-DUPLEX VAD STATE MACHINE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   TTS speaks      →  startVad() creates FullDuplexVadEngine (VOICE_COMMUNICATION)
 *   User speaks     →  Silero isSpeech() → onVoiceDetected on Main
 *   Interrupt fired →  1. thisEngine.stop()     ← MIC RELEASED SYNCHRONOUSLY
 *                      2. tts.stop()            ← TTS STOPPED
 *                      3. listener.onVadInterrupted()  ← caller starts STT
 *                      (VoskEngine can now safely open VOICE_RECOGNITION)
 *   TTS ends naturally → stopVad("tts_done") stops engine
 *
 * ─────────────────────────────────────────────────────────────────────────
 * RACE CONDITION PREVENTION
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   [vadEngineRef]    — AtomicReference<FullDuplexVadEngine?>.
 *                       Identity check in onVoiceDetected: compareAndSet
 *                       ensures only the CURRENT engine can fire an interrupt.
 *                       Stale callbacks from replaced engines are dropped.
 *
 *   [vadInterruptFired] — AtomicBoolean CAS gate per TTS turn.
 *                         First speech frame wins. All subsequent frames from
 *                         the same or overlapping detections are dropped.
 *
 *   These two guards together prevent:
 *     a) Double-interrupt within the same Silero session
 *     b) Late-arriving callback from a stopped engine
 *     c) Natural TTS-end racing against a VAD detection
 *
 * ─────────────────────────────────────────────────────────────────────────
 * STREAMING TTS — lastQueuedUtteranceId + ttsStreamActive
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   onDone fires for EVERY QUEUE_ADD utterance, not just the last.
 *   We fire onSpeakingDone() only when BOTH conditions hold:
 *     (a) utteranceId == lastQueuedUtteranceId (this is the final chunk)
 *     (b) !ttsStreamActive (ttsStreamFlush() has been called)
 *
 *   ttsStreamFlush() always queues a sentinel utterance (even for empty
 *   tail) so lastQueuedUtteranceId always points to a real onDone event.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * AUDIO SOURCE EXCLUSIVITY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   VAD    uses VOICE_COMMUNICATION (AEC pipeline, HW echo cancellation).
 *   Vosk   uses VOICE_RECOGNITION   (different DSP path, different handle).
 *   They NEVER overlap — VAD is created only when TTS is playing (Vosk idle),
 *   and its AudioRecord is released SYNCHRONOUSLY in stop() before the
 *   onVadInterrupted() callback even returns.
 */
class VoiceManager(
    private val context: Context,
    private val listener: VoiceListener
) {

    @Volatile private var isDestroyed = false

    // ─────────────────────────────────────────────────────────────────────
    // Public listener interface
    // ─────────────────────────────────────────────────────────────────────

    interface VoiceListener {
        fun onWakeWordDetected()
        fun onSpeechResult(text: String)
        fun onError(error: String)
        fun onSpeakingStarted()           = Unit
        fun onSpeakingDone()              = Unit
        fun onListeningStarted()          = Unit
        fun onListeningStopped()          = Unit
        fun onPartialResult(text: String) = Unit
        /**
         * Fired on the MAIN thread when full-duplex VAD confirms user speech
         * during TTS playback. TTS has ALREADY been stopped and the VAD
         * microphone has ALREADY been released before this is called.
         * The caller should immediately start STT.
         *
         * onSpeakingDone() is NOT fired for the interrupted utterance.
         */
        fun onVadInterrupted() = Unit
    }

    // ─────────────────────────────────────────────────────────────────────
    // TTS
    // ─────────────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // ── AudioFocus — claimed when TTS starts, released when it stops ──────────
    // Without AudioFocus, TTS audio fights with music apps and notification
    // sounds causing distortion or dropped utterances. With it, media pauses
    // automatically. Focus is abandoned in stopSpeaking() and destroy().
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var audioFocusHeld = false
    private val audioFocusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.i(TAG, "AIRI_RUNTIME AUDIO_FOCUS_LOST transient=${focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT}")
                        audioFocusHeld = false
                        // Stop TTS if focus is permanently lost (another app took over)
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            stopSpeaking()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "AIRI_RUNTIME AUDIO_FOCUS_REGAINED")
                        audioFocusHeld = true
                    }
                }
            }
            .build()
    } else null

    // Tracks the utteranceId of the most-recently queued TTS chunk.
    // onSpeakingDone fires only when onDone receives this exact id AND
    // ttsStreamActive is false (ttsStreamFlush has been called).
    private val lastQueuedUtteranceId = AtomicReference<String?>(null)

    // Monotonic generation counter. Incremented on every new TTS turn
    // (speak() or ttsStreamReset()). Future use: stale-callback detection.
    private val ttsGeneration = AtomicInteger(0)

    private val ttsStreamBuffer = StringBuilder()
    @Volatile private var ttsStreamActive = false

    // ─────────────────────────────────────────────────────────────────────
    // Threading
    // ─────────────────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────────────────────────────────
    // STT
    // ─────────────────────────────────────────────────────────────────────

    private val sttScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sttEngine: VoskEngine? = null
    @Volatile private var sttModel: Model? = null
    @Volatile private var sttJob: Job? = null
    @Volatile private var sttActive = false
    @Volatile private var platformRecognizer: SpeechRecognizer? = null

    // ─────────────────────────────────────────────────────────────────────
    // Full-duplex VAD
    // ─────────────────────────────────────────────────────────────────────

    // AtomicReference for thread-safe engine swap and identity checks.
    private val vadEngineRef = AtomicReference<FullDuplexVadEngine?>(null)

    // Separate scope so cancellation of STT never reaches VAD and vice versa.
    private val vadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-turn CAS gate. Set to false in startVad(), true on first detection
    // or in stopVad(). Prevents double-interrupt within the same session.
    private val vadInterruptFired = AtomicBoolean(true) // starts "fired" (inactive)

    // Prevents double-startVad during rapid TTS resets.
    @Volatile private var vadArmed = false

    // ─────────────────────────────────────────────────────────────────────
    // Wake-word
    // ─────────────────────────────────────────────────────────────────────

    private var isListeningForWakeWord = false

    // ─────────────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────────────

    init {
        VoskModelManager.init(context.applicationContext)
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                           result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    tts?.setLanguage(Locale.ENGLISH)
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(buildUtteranceProgressListener())
                Log.d(TAG, "TextToSpeech initialized")
            } else {
                ttsReady = false
                Log.w(TAG, "TextToSpeech init failed status=$status")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTTERANCE PROGRESS LISTENER
    // ─────────────────────────────────────────────────────────────────────

    private fun buildUtteranceProgressListener() = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "AIRI_RUNTIME TTS_UTTERANCE_START id=$utteranceId")
            postToMain { listener.onSpeakingStarted() }
        }

        override fun onDone(utteranceId: String?) {
            val last = lastQueuedUtteranceId.get()
            val streamDone = !ttsStreamActive  // snapshot before any state changes
            Log.d(TAG, "AIRI_RUNTIME TTS_UTTERANCE_DONE id=$utteranceId last=$last streamDone=$streamDone")

            // Fire onSpeakingDone only when:
            //   (a) this is the final queued utterance, AND
            //   (b) streaming has been flushed (no more chunks coming).
            //
            // Without (b): if TTS plays chunk N faster than LLM generates
            // chunk N+1, onDone for N fires while ttsStreamActive is still
            // true. Firing onSpeakingDone here would re-arm Vosk prematurely.
            if (utteranceId != null && utteranceId == last && streamDone) {
                stopVad("tts_done_final_utterance")
                postToMain {
                    com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "IDLE")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                    listener.onSpeakingDone()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "AIRI_RUNTIME TTS_UTTERANCE_ERROR id=$utteranceId")
            stopVad("tts_error")
            postToMain {
                com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "IDLE")
                com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                listener.onSpeakingDone()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TTS — ONE-SHOT
    // ─────────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready — speak() skipped")
            return
        }
        requestAudioFocus()
        ttsGeneration.incrementAndGet()
        val utteranceId = "airi_${System.currentTimeMillis()}"
        lastQueuedUtteranceId.set(utteranceId)
        // QUEUE_FLUSH clears any stale streaming chunks before speaking.
        tts!!.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "SPEAKING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "SPEAKING") }
        Log.i(TAG, "AIRI_RUNTIME TTS_SPEAK chars=${text.length} utteranceId=$utteranceId")
        startVad()
    }

    // ─────────────────────────────────────────────────────────────────────
    // TTS — STREAMING
    // ─────────────────────────────────────────────────────────────────────

    fun ttsStreamReset() {
        ttsStreamBuffer.setLength(0)
        ttsStreamActive = true
        ttsGeneration.incrementAndGet()
        Log.i(TAG, "AIRI_RUNTIME TTS_STREAM_RESET")
    }

    fun ttsStreamAppend(delta: String) {
        if (!ttsReady || tts == null || !ttsStreamActive) return
        ttsStreamBuffer.append(delta)

        var flushed = 0
        while (true) {
            val s = ttsStreamBuffer
            var idx = -1
            for (i in 0 until s.length) {
                val c = s[i]
                if (c == '.' || c == '!' || c == '?' || c == '؟' ||
                    c == '،' || c == ',' || c == '\n') {
                    idx = i; break
                }
            }
            if (idx < 0 && s.length >= 80) {
                for (wi in minOf(79, s.length - 1) downTo 20) {
                    if (s[wi] == ' ') { idx = wi; break }
                }
            }
            if (idx < 0) break
            val sentence = s.substring(0, idx + 1).trim()
            s.delete(0, idx + 1)
            if (sentence.isNotEmpty()) {
                val utteranceId = "airi_stream_${System.currentTimeMillis()}_$flushed"
                lastQueuedUtteranceId.set(utteranceId)
                tts!!.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
                flushed++
            }
        }

        if (flushed > 0) {
            requestAudioFocus()
            com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "SPEAKING")
            com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "SPEAKING") }
            Log.i(TAG, "AIRI_RUNTIME TTS_STREAM_FLUSH chunks=$flushed remaining=${ttsStreamBuffer.length}")
            startVad()
        }
    }

    /**
     * Flushes any tail not yet terminated by punctuation. ALWAYS queues a
     * sentinel utterance (even if the tail is empty, using a zero-width
     * space) so [lastQueuedUtteranceId] always points to a real onDone
     * event. Without the sentinel, an empty tail would leave
     * [lastQueuedUtteranceId] pointing to the last streaming chunk — which
     * may have already had its onDone fire while [ttsStreamActive] was still
     * true, silently dropping the [onSpeakingDone] callback.
     */
    fun ttsStreamFlush() {
        if (!ttsReady || tts == null || !ttsStreamActive) return
        val tail = ttsStreamBuffer.toString().trim()
        ttsStreamBuffer.setLength(0)
        ttsStreamActive = false  // mark streaming complete BEFORE queueing sentinel

        // Always queue something so onDone fires for this exact utteranceId.
        // Zero-width space is inaudible but processes through the TTS queue.
        val content = tail.ifEmpty { "\u200B" }
        val utteranceId = "airi_stream_tail_${System.currentTimeMillis()}"
        lastQueuedUtteranceId.set(utteranceId)
        tts!!.speak(content, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.i(TAG, "AIRI_RUNTIME TTS_STREAM_TAIL chars=${content.length} sentinel=${tail.isEmpty()} id=$utteranceId")
    }

    // ─────────────────────────────────────────────────────────────────────
    // TTS — STOP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Stops TTS and VAD immediately. Does NOT fire onSpeakingDone —
     * callers (ChatScreen) handle state transitions directly.
     */
    fun stopSpeaking() {
        stopVad("stop_speaking")
        ttsStreamActive = false
        ttsStreamBuffer.setLength(0)
        val wasSpeaking = tts?.isSpeaking == true
        tts?.stop()
        abandonAudioFocus()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "IDLE")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
        Log.i(TAG, "AIRI_RUNTIME TTS_STOPPED wasSpeaking=$wasSpeaking")
    }

    // ── AudioFocus helpers ─────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (audioFocusHeld) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "AIRI_RUNTIME AUDIO_FOCUS_REQUESTED granted=$audioFocusHeld")
    }

    private fun abandonAudioFocus() {
        if (!audioFocusHeld) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusHeld = false
        Log.d(TAG, "AIRI_RUNTIME AUDIO_FOCUS_ABANDONED")
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    // ─────────────────────────────────────────────────────────────────────
    // FULL-DUPLEX VAD — internal
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Arms the VAD for the current TTS turn. Idempotent — no-op if already
     * armed. Called by speak() and ttsStreamAppend() on first flush.
     *
     * INTERRUPT HANDLER ORDERING (critical for audio-source exclusivity):
     *   1. thisEngine.stop()        — releases VOICE_COMMUNICATION AudioRecord SYNCHRONOUSLY
     *   2. vadEngineRef.set(null)   — prevent stale callbacks
     *   3. tts.stop()               — halt TTS playback
     *   4. listener.onVadInterrupted() — caller opens VOICE_RECOGNITION (Vosk)
     *
     * VoskEngine can safely open its AudioRecord in step 4 because step 1
     * has ALREADY completed before this lambda returns.
     */
    private fun startVad() {
        if (vadArmed) return
        if (!ttsReady) return
        vadArmed = true
        vadInterruptFired.set(false)  // reset CAS gate for this turn

        // Stop any lingering engine from the previous turn (safety net).
        val old = vadEngineRef.getAndSet(null)
        old?.stop()

        Log.i(TAG, "AIRI_RUNTIME VAD_ARMING")

        // Capture `thisEngine` for the identity check inside the callback.
        // The lambda below captures it by reference, and the reference is
        // assigned after construction (chicken-and-egg resolved by the local var).
        var thisEngine: FullDuplexVadEngine? = null

        val engine = FullDuplexVadEngine(
            context = context.applicationContext,
            onVoiceDetected = {
                // Called on Main thread by FullDuplexVadEngine.

                // ── Guard 1: CAS gate ───────────────────────────────────
                // Only the FIRST speech frame per turn proceeds.
                // Subsequent frames from the same voice burst are dropped.
                if (!vadInterruptFired.compareAndSet(false, true)) {
                    Log.w(TAG, "VAD_DOUBLE_INTERRUPT_DROPPED — CAS already fired")
                    return@FullDuplexVadEngine
                }

                // ── Guard 2: Engine identity check ──────────────────────
                // If stopVad() was called between detection and this callback
                // (natural TTS end racing against VAD), vadEngineRef will have
                // been cleared. compareAndSet fails → stale callback dropped.
                val me = thisEngine
                if (me == null || !vadEngineRef.compareAndSet(me, null)) {
                    Log.w(TAG, "VAD_STALE_CALLBACK_DROPPED — engine already replaced or cleared")
                    return@FullDuplexVadEngine
                }

                vadArmed = false
                Log.i(TAG, "AIRI_RUNTIME VAD_INTERRUPT_EXECUTING")

                // ── Step 1: SYNCHRONOUS mic release ─────────────────────
                // me.stop() calls AudioRecord.stop() + release() synchronously
                // and returns. The VOICE_COMMUNICATION hardware path is free.
                // VoskEngine can now safely open VOICE_RECOGNITION.
                me.stop()

                // ── Step 2: Stop TTS ────────────────────────────────────
                val wasSpeaking = tts?.isSpeaking == true
                tts?.stop()
                ttsStreamActive = false
                ttsStreamBuffer.setLength(0)
                com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "INTERRUPTING")
                com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "INTERRUPTING") }
                Log.i(TAG, "AIRI_RUNTIME TTS_STOPPED_BY_VAD wasSpeaking=$wasSpeaking")

                // ── Step 3: Notify listener ─────────────────────────────
                // Mic is guaranteed free. Caller (ChatScreen) will call
                // startInAppStt() which opens VOICE_RECOGNITION AudioRecord.
                listener.onVadInterrupted()
            },
            onStopped = { reason ->
                // Called on Main when VAD exits without detection.
                // Clear engine reference and reset armed flag for next turn.
                val me = thisEngine
                if (me != null) vadEngineRef.compareAndSet(me, null)
                vadArmed = false
                Log.i(TAG, "AIRI_RUNTIME VAD_STOPPED_NO_INTERRUPT reason=$reason")
            }
        )

        thisEngine = engine
        vadEngineRef.set(engine)
        engine.start(vadScope)
    }

    /**
     * Stops and nullifies the current VAD engine. Idempotent.
     *
     * Sets [vadInterruptFired] to true FIRST so any in-flight detection
     * racing on the IO thread sees the CAS already consumed and drops.
     */
    private fun stopVad(reason: String) {
        vadInterruptFired.set(true)  // block any racing detection
        vadArmed = false
        val e = vadEngineRef.getAndSet(null) ?: return
        e.stop()
        Log.i(TAG, "AIRI_RUNTIME VAD_STOP reason=$reason")
    }

    /**
     * Public API called by ChatScreen at ALL manual stop-speaking sites
     * (mic tap, voice-chat tap, user typing, manual replay) to prevent
     * lingering VAD loops after TTS is externally interrupted.
     */
    fun stopVadIfRunning() {
        stopVad("external_stop")
    }

    // ─────────────────────────────────────────────────────────────────────
    // WAKE-WORD
    // ─────────────────────────────────────────────────────────────────────

    fun startWakeWordDetection() {
        if (isListeningForWakeWord) return
        isListeningForWakeWord = true
        HotwordService.start(context.applicationContext)
        Log.d(TAG, "Hotword service started")
    }

    fun stopWakeWordDetection() {
        if (!isListeningForWakeWord) return
        isListeningForWakeWord = false
        HotwordService.stop(context.applicationContext)
        Log.d(TAG, "Hotword service stopped")
    }

    // ─────────────────────────────────────────────────────────────────────
    // STT — VOSK + PLATFORM FALLBACK
    // ─────────────────────────────────────────────────────────────────────

    fun isSpeechRecognitionAvailable(): Boolean =
        VoskModelManager.isReady(context.applicationContext) ||
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)

    fun isVoskAvailable(): Boolean =
        VoskModelManager.isReady(context.applicationContext)

    fun startSpeechToText() {
        if (sttActive) {
            Log.d(TAG, "STT already active — ignoring duplicate start")
            return
        }
        // Stop VAD BEFORE opening any AudioRecord for STT.
        stopVad("stt_starting")

        val androidAvail = SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
        Log.i(TAG, "AIRI_RUNTIME STT_AVAILABILITY android=$androidAvail vosk=${isVoskAvailable()}")

        if (androidAvail) {
            startPlatformSpeechToText(); return
        }
        if (!isVoskAvailable()) {
            listener.onError("stt_unavailable"); return
        }

        sttActive = true
        listener.onListeningStarted()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "LISTENING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "LISTENING") }

        sttJob = sttScope.launch {
            val model = sttModel ?: VoskModelManager.loadActiveModel(context.applicationContext)
            if (model == null) {
                sttActive = false
                postToMain { listener.onListeningStopped() }
                postToMain { listener.onError("vosk_model_load_failed") }
                return@launch
            }
            sttModel = model
            val engine = VoskEngine(context.applicationContext, model)
            sttEngine = engine
            engine.start(
                scope     = sttScope,
                onPartial = { p -> postToMain { listener.onPartialResult(p) } },
                onFinal   = { text ->
                    sttActive = false
                    sttEngine?.release(); sttEngine = null
                    postToMain {
                        listener.onListeningStopped()
                        if (text.isNotBlank()) listener.onSpeechResult(text)
                        else listener.onError("stt_empty_result")
                    }
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                },
                onError   = { err ->
                    sttActive = false
                    sttEngine?.release(); sttEngine = null
                    postToMain { listener.onListeningStopped(); listener.onError(err) }
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
            )
        }
    }

    fun stopSpeechToText() {
        sttEngine?.stop()
        platformRecognizer?.let { r -> try { r.stopListening() } catch (_: Throwable) {} }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PLATFORM STT FALLBACK
    // ─────────────────────────────────────────────────────────────────────

    private fun startPlatformSpeechToText() {
        if (isDestroyed) return
        sttActive = true
        listener.onListeningStarted()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "LISTENING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "LISTENING") }

        mainHandler.post {
            if (isDestroyed || !sttActive) return@post
            val rec = try {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } catch (t: Throwable) {
                sttActive = false
                listener.onListeningStopped()
                listener.onError("stt_unavailable")
                return@post
            }
            platformRecognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) listener.onPartialResult(text)
                }
                override fun onResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    sttActive = false
                    try { rec.destroy() } catch (_: Throwable) {}
                    if (platformRecognizer === rec) platformRecognizer = null
                    listener.onListeningStopped()
                    if (text.isNotBlank()) listener.onSpeechResult(text)
                    else listener.onError("stt_empty_result")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
                override fun onError(code: Int) {
                    sttActive = false
                    try { rec.destroy() } catch (_: Throwable) {}
                    if (platformRecognizer === rec) platformRecognizer = null
                    listener.onListeningStopped()
                    listener.onError("stt_platform_error_$code")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                         RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                rec.startListening(intent)
            } catch (t: Throwable) {
                sttActive = false
                try { rec.destroy() } catch (_: Throwable) {}
                platformRecognizer = null
                listener.onListeningStopped()
                listener.onError("stt_unavailable")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // STOP ALL / DESTROY
    // ─────────────────────────────────────────────────────────────────────

    fun stopAll() {
        stopWakeWordDetection()
        stopSpeechToText()
        stopVad("stop_all")
        tts?.stop()
    }

    fun destroy() {
        Log.d(TAG, "VoiceManager destroying...")
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        isListeningForWakeWord = false

        sttJob?.cancel()
        sttEngine?.release(); sttEngine = null
        try { sttModel?.close() } catch (_: Throwable) {}
        sttModel = null
        platformRecognizer?.let { r -> try { r.destroy() } catch (_: Throwable) {} }
        platformRecognizer = null
        sttScope.cancel()

        stopVad("destroy")
        vadScope.cancel()

        ttsStreamActive = false
        ttsStreamBuffer.setLength(0)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        abandonAudioFocus()

        Log.i(TAG, "AIRI_RUNTIME VOICE_MANAGER_DESTROYED")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────

    private inline fun postToMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private companion object {
        const val TAG = "AIRI_VOICE"
    }
}
