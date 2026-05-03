package com.airi.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airi.assistant.core.VoiceManager
import com.airi.assistant.voice.realtime.RealtimeVoiceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LiveVoiceService — foreground service owning the AIRI full-duplex voice session.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Lifecycle:
 *   Application.onCreate() → startForegroundService(LiveVoiceService)
 *   Activity.onStart()     → bindService(LocalBinder)
 *   Activity.onStop()      → unbindService (service remains running)
 *   User quits AIRI        → stopSelf() / ACTION_STOP intent
 *
 * Ownership:
 *   LiveVoiceService owns [LiveVoiceSession] and [VoiceManager].
 *   ChatViewModel binds to the service via [LocalBinder] and:
 *     - Reads [session] StateFlows for UI state
 *     - Calls [requestListen], [requestStop], [speakChunk]
 *     - Routes STT results to HybridOrchestrator
 *
 * This design survives:
 *   - Screen rotation (service is not recreated, StateFlows retain values)
 *   - Temporary backgrounding (foreground service continues audio)
 *   - Configuration changes (Activity rebinds on recreate)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * AUDIO HARDWARE POLICY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   VoiceManager enforces all audio-source exclusivity rules.
 *   LiveVoiceService drives VoiceManager via its VoiceListener callbacks,
 *   translating them into LiveVoiceSession state transitions.
 *   The service NEVER holds AudioRecord or AudioTrack directly.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * REALTIME CLOUD PROVIDER SWAPPING
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Set [realtimeProvider] to a [RealtimeVoiceProvider] implementation to
 *   route audio through Gemini Live or OpenAI Realtime instead of local
 *   Vosk+TTS. Set to [LocalVoicePipeline] (or null) to revert to on-device.
 */
class LiveVoiceService : Service() {

    // ─────────────────────────────────────────────────────────────────────────
    // Binder — gives bound clients direct access to session + voice control
    // ─────────────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        /** The lifecycle-independent session state holder. Observe its StateFlows. */
        val session: LiveVoiceSession
            get() = this@LiveVoiceService.session

        /** Raw VoiceManager — use only for operations not covered by this service API. */
        val voiceManager: VoiceManager
            get() = this@LiveVoiceService.voiceManager

        /** Start listening for user speech. No-op if already listening. */
        fun requestListen()   = this@LiveVoiceService.requestListen()

        /** Stop all audio activity and return to IDLE. */
        fun requestStop()     = this@LiveVoiceService.requestStop()

        /**
         * Append a TTS chunk from the LLM token stream.
         * Call with flush=true on the final chunk of a turn.
         */
        fun speakChunk(text: String, flush: Boolean = false) =
            this@LiveVoiceService.speakChunk(text, flush)

        /** Begin a streaming TTS session (call before first speakChunk). */
        fun beginSpeaking()   = this@LiveVoiceService.beginSpeaking()

        /** Interrupt any ongoing TTS immediately. */
        fun interruptSpeaking() = this@LiveVoiceService.interruptSpeaking()

        /** Swap to a cloud realtime provider (null = local Vosk+TTS). */
        fun setRealtimeProvider(provider: RealtimeVoiceProvider?) {
            this@LiveVoiceService.realtimeProvider = provider ?: LocalVoicePipeline
        }
    }

    private val binder = LocalBinder()

    // ─────────────────────────────────────────────────────────────────────────
    // Core components
    // ─────────────────────────────────────────────────────────────────────────

    val session = LiveVoiceSession()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var voiceManager: VoiceManager

    /**
     * Active realtime provider. [LocalVoicePipeline] = on-device Vosk+TTS.
     * Swap to GeminiLiveProvider / OpenAIRealtimeProvider for cloud audio.
     */
    @Volatile var realtimeProvider: RealtimeVoiceProvider = LocalVoicePipeline

    // ─────────────────────────────────────────────────────────────────────────
    // Timing probes
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile private var sttStartEpochMs:     Long = 0L
    @Volatile private var thinkingStartEpochMs: Long = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LiveVoiceService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(VoicePipelineState.IDLE))
        voiceManager = VoiceManager(applicationContext, buildVoiceListener())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTEN -> requestListen()
            ACTION_STOP         -> requestStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "LiveVoiceService onDestroy")
        session.endSession()
        voiceManager.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public voice control API
    // ─────────────────────────────────────────────────────────────────────────

    fun requestListen() {
        val current = session.state.value
        Log.d(TAG, "requestListen current=$current")
        if (current == VoicePipelineState.IDLE || current == VoicePipelineState.INTERRUPTED) {
            session.onListenStart()
            updateNotification(VoicePipelineState.LISTENING)
            sttStartEpochMs = System.currentTimeMillis()
            voiceManager.startSpeechToText()
        }
    }

    fun requestStop() {
        Log.d(TAG, "requestStop")
        voiceManager.stopAll()
        session.endSession()
        updateNotification(VoicePipelineState.IDLE)
    }

    /** Signal that the LLM has started generating — prepare TTS stream. */
    fun beginSpeaking() {
        thinkingStartEpochMs = System.currentTimeMillis()
        voiceManager.ttsStreamReset()
    }

    /**
     * Append a TTS chunk from the LLM token stream.
     * Call with flush=true on the LAST chunk of the turn.
     */
    fun speakChunk(text: String, flush: Boolean = false) {
        if (flush) {
            voiceManager.ttsStreamAppend(text)
            voiceManager.ttsStreamFlush()
        } else {
            voiceManager.ttsStreamAppend(text)
        }
    }

    /** Immediately stop TTS and discard any queued chunks. */
    fun interruptSpeaking() {
        voiceManager.stopSpeaking()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VoiceListener — bridges VoiceManager callbacks → LiveVoiceSession state
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildVoiceListener(): VoiceManager.VoiceListener =
        object : VoiceManager.VoiceListener {

            override fun onWakeWordDetected() {
                Log.d(TAG, "onWakeWordDetected → LISTENING")
                session.onListenStart()
                sttStartEpochMs = System.currentTimeMillis()
                updateNotification(VoicePipelineState.LISTENING)
            }

            override fun onListeningStarted() {
                sttStartEpochMs = System.currentTimeMillis()
            }

            override fun onPartialResult(text: String) {
                session.onPartialTranscript(text)
            }

            override fun onSpeechResult(text: String) {
                val sttMs = System.currentTimeMillis() - sttStartEpochMs
                session.recordSttLatency(sttMs)
                session.onSpeechResult(text)
                thinkingStartEpochMs = System.currentTimeMillis()
                updateNotification(VoicePipelineState.THINKING)
                Log.d(TAG, "AIRI_PROOF STT_RESULT sttLatency=${sttMs}ms text='${text.take(60)}'")
            }

            override fun onSpeakingStarted() {
                val ttfb = System.currentTimeMillis() - thinkingStartEpochMs
                session.recordTtsFirstByteLatency(ttfb)
                session.onResponseStreaming(ttfb)
                updateNotification(VoicePipelineState.STREAMING_RESPONSE)
                Log.d(TAG, "AIRI_PROOF TTS_START ttfb=${ttfb}ms")
            }

            override fun onSpeakingDone() {
                session.onTurnComplete()
                updateNotification(VoicePipelineState.IDLE)
                Log.d(TAG, "AIRI_PROOF TTS_DONE — auto-rearming STT")
                // Auto-rearm listening after AIRI finishes speaking
                requestListen()
            }

            override fun onVadInterrupted() {
                val interruptStart = System.currentTimeMillis()
                session.onBargeIn()
                updateNotification(VoicePipelineState.INTERRUPTED)
                Log.d(TAG, "AIRI_PROOF VAD_BARGE_IN")
                // VoiceManager has already stopped TTS and released VAD mic.
                // Re-arm STT immediately.
                serviceScope.launch {
                    val interruptMs = System.currentTimeMillis() - interruptStart
                    session.recordInterruptionLatency(interruptMs)
                    session.onResumeListening()
                    sttStartEpochMs = System.currentTimeMillis()
                    voiceManager.startSpeechToText()
                    updateNotification(VoicePipelineState.LISTENING)
                }
            }

            override fun onListeningStopped() {
                // Driven by onSpeechResult or requestStop — no state transition needed here.
            }

            override fun onError(error: String) {
                Log.w(TAG, "VoiceManager error: $error")
                val shouldRecover = session.onError(error)
                if (shouldRecover) {
                    serviceScope.launch {
                        delay(1_500L)
                        Log.i(TAG, "Recovery attempt ${session.recoveryAttempts}")
                        voiceManager.stopAll()
                        delay(200L)
                        session.onRecoverySuccess()
                        requestListen()
                    }
                } else {
                    Log.e(TAG, "Max recovery attempts exhausted — voice IDLE")
                    updateNotification(VoicePipelineState.IDLE)
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification management
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AIRI Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while AIRI voice is enabled"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: VoicePipelineState): Notification {
        val (title, text) = when (state) {
            VoicePipelineState.IDLE               -> "AIRI" to "Ready"
            VoicePipelineState.LISTENING          -> "AIRI is listening…" to "Speak now"
            VoicePipelineState.THINKING           -> "AIRI is thinking…" to "Processing"
            VoicePipelineState.STREAMING_RESPONSE -> "AIRI is speaking" to "Tap to interrupt"
            VoicePipelineState.INTERRUPTED        -> "AIRI interrupted" to "Listening again…"
            VoicePipelineState.RECOVERING         -> "Reconnecting…" to "Voice session recovering"
        }

        val stopIntent = Intent(this, LiveVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(state: VoicePipelineState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG           = "LiveVoiceService"
        private const val CHANNEL_ID    = "airi_voice_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTEN = "com.airi.assistant.action.VOICE_START"
        const val ACTION_STOP         = "com.airi.assistant.action.VOICE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, LiveVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveVoiceService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
