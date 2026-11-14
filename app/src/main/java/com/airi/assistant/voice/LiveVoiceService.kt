package com.airi.assistant.voice

import android.app.Notification
import com.airi.assistant.core.ExecutionTraceGateway
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.VoiceManager
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.voice.realtime.LocalVoicePipeline
import com.airi.assistant.voice.realtime.RealtimeProviderFactory
import com.airi.assistant.voice.realtime.RealtimeVoiceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.airi.assistant.R

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
 * ─────────────────────────────────────────────────────────────────────────
 * AUDIO FOCUS POLICY ()
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   [requestListen] acquires AUDIOFOCUS_GAIN_TRANSIENT before starting STT.
 *   If the system denies focus ([AudioManager.AUDIOFOCUS_REQUEST_FAILED]),
 *   the listen request is silently dropped and logged — another app (phone
 *   call, media player) has exclusive audio access.
 *
 *   [requestStop] and [onDestroy] always call [abandonAudioFocus] so focus
 *   is returned promptly to the interrupted app.
 *
 *   Focus-change listener:
 *     LOSS / LOSS_TRANSIENT  → stop voice (media player taking over)
 *     GAIN                   → re-arm STT (we regained focus)
 */
class LiveVoiceService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        val session: LiveVoiceSession
            get() = this@LiveVoiceService.session

        val voiceManager: VoiceManager
            get() = this@LiveVoiceService.voiceManager

        fun requestListen()   = this@LiveVoiceService.requestListen()
        fun requestStop()     = this@LiveVoiceService.requestStop()

        fun speakChunk(text: String, flush: Boolean = false) =
            this@LiveVoiceService.speakChunk(text, flush)

        fun beginSpeaking()   = this@LiveVoiceService.beginSpeaking()
        fun interruptSpeaking() = this@LiveVoiceService.interruptSpeaking()

        fun setRealtimeProvider(provider: RealtimeVoiceProvider?) {
            this@LiveVoiceService.realtimeProvider = provider ?: LocalVoicePipeline
        }

        fun startRealtimeDuplex(systemPrompt: String, apiKey: String, voiceId: String = "alloy") =
            this@LiveVoiceService.startRealtimeDuplex(systemPrompt, apiKey, voiceId)

        fun startConfiguredRealtimeDuplex(systemPrompt: String, voiceId: String = "alloy") =
            this@LiveVoiceService.startConfiguredRealtimeDuplex(systemPrompt, voiceId)

        fun stopRealtimeDuplex() = this@LiveVoiceService.stopRealtimeDuplex()
    }

    private val binder = LocalBinder()

    // ── Core components ───────────────────────────────────────────────────────

    val session = LiveVoiceSession()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var voiceManager: VoiceManager

    private lateinit var voiceAgentRouter: VoiceAgentRouter

    private val interruptController = VoiceInterruptController(serviceScope)
    private val incrementalTts by lazy { IncrementalTtsEngine(applicationContext) }

    @Volatile var realtimeProvider: RealtimeVoiceProvider = LocalVoicePipeline
    @Volatile private var realtimeTransport: RealtimeAudioTransport? = null
    @Volatile private var listenRequestedByUser = false
    @Volatile private var activeSttGeneration: Long = 0L
    private var recoveryJob: Job? = null
    private var routeJob: Job? = null

    // ── Audio focus () ─────────────────────────────────────────────────

    private lateinit var audioManager: AudioManager

    /**
     * AudioFocusRequest built once in [onCreate] and reused for all
     * [requestListen] / [abandonAudioFocus] cycles.
     * Null on pre-API-26 devices (handled by the deprecated API below).
     */
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost; pausing voice")
                pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (listenRequestedByUser) {
                    Log.i(TAG, "Audio focus regained; resuming requested voice session")
                    requestListen()
                }
            }
        }
    }

    // ── Timing probes ─────────────────────────────────────────────────────────

    @Volatile private var sttStartEpochMs:     Long = 0L
    @Volatile private var thinkingStartEpochMs: Long = 0L

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LiveVoiceService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(VoicePipelineState.IDLE))

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Build AudioFocusRequest once (API 26+). Pre-26 uses deprecated path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
        }

        voiceManager = VoiceManager(applicationContext, buildVoiceListener())
        voiceAgentRouter = VoiceAgentRouter(
            appContext = applicationContext,
            orchestrator = ServiceLocator.productionOrchestrator,
            confirmationGateProvider = { ServiceLocator.voiceConfirmationGate }
        )
        Log.i(TAG, "AIRI VOICE_AGENT_ROUTER_INIT")

        interruptController.onStopTts          = { voiceManager.stopSpeaking() }
        interruptController.onCancelGeneration = { serviceScope.launch { voiceManager.stopAll() } }
        interruptController.onRearmStt         = {
            serviceScope.launch {
                if (!listenRequestedByUser) return@launch
                session.onResumeListening()
                sttStartEpochMs = System.currentTimeMillis()
                voiceManager.startSpeechToText()
                updateNotification(VoicePipelineState.LISTENING)
            }
        }
        interruptController.onTransitionTo = { state ->
            updateNotification(state)
        }
        incrementalTts.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTEN -> requestListen()
            ACTION_STOP         -> requestStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        restoreProviderPreference()
        return binder
    }

    /**
     * Restores the user's saved cloud voice provider preference from SharedPreferences.
     *
     * Called on every [onBind] so that a new UI binding always reflects the
     * persisted selection — even after process death and recreation.
     */
    private fun restoreProviderPreference() {
        val requested = VoicePreferencesStore.load(this).cloudVoiceProvider
        realtimeProvider = RealtimeProviderFactory.create(requested)
        Log.i(TAG, "AIRI VOICE_PROVIDER_SELECTED requested=$requested active=${realtimeProvider.name}")
    }

    override fun onDestroy() {
        Log.i(TAG, "LiveVoiceService onDestroy")
        abandonAudioFocus()
        routeJob?.cancel()
        routeJob = null
        realtimeTransport?.destroy()
        realtimeTransport = null
        session.endSession()
        voiceManager.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public voice control API ──────────────────────────────────────────────

    /**
     * Request audio focus and start STT listening.
     *
     * If the system denies audio focus ([AudioManager.AUDIOFOCUS_REQUEST_FAILED]),
     * the request is dropped and logged. Another app (phone call, media player)
     * currently holds exclusive audio access.
     */
    fun requestListen() {
        listenRequestedByUser = true
        // ACTION_START_LISTEN can arrive before onBind after process recreation;
        // refresh the persisted selection so it cannot silently fall back local.
        restoreProviderPreference()
        val current = session.state.value
        Log.d(TAG, "requestListen current=$current")
        if (current == VoicePipelineState.IDLE || current == VoicePipelineState.INTERRUPTED) {
            val focusGranted = requestAudioFocus()
            if (!focusGranted) {
                Log.w(TAG, "AIRI AUDIOFOCUS_REQUEST_FAILED — not starting STT")
                ExecutionTraceGateway.emit("Audio focus denied — another app is using audio",
                    com.airi.assistant.ui.activity.ActivityCategory.VOICE)
                return
            }
            // A selected cloud provider owns both microphone capture and
            // speaker playback. Do not start the local VoiceManager as a
            // competing AudioRecord owner.
            if (realtimeProvider !== LocalVoicePipeline) {
                startConfiguredRealtimeDuplex(systemPrompt = "")
                return
            }
            val sessionId = session.beginSession()
            updateNotification(VoicePipelineState.LISTENING)
            sttStartEpochMs = System.currentTimeMillis()
            Log.i(TAG, "AIRI VOICE_LISTEN_BEGIN session=$sessionId")
            activeSttGeneration = 0L
            voiceManager.startSpeechToText()
            activeSttGeneration = voiceManager.currentSttGeneration
        }
    }

    fun requestStop() {
        Log.d(TAG, "requestStop")
        listenRequestedByUser = false
        recoveryJob?.cancel()
        recoveryJob = null
        routeJob?.cancel()
        routeJob = null
        stopRealtimeDuplex()
        abandonAudioFocus()
        voiceManager.stopAll()
        session.endSession()
        updateNotification(VoicePipelineState.IDLE)
    }

    private fun pauseForFocusLoss() {
        recoveryJob?.cancel()
        recoveryJob = null
        routeJob?.cancel()
        routeJob = null
        stopRealtimeDuplex()
        voiceManager.stopAll()
        session.endSession()
        updateNotification(VoicePipelineState.IDLE)
    }

    fun beginSpeaking() {
        thinkingStartEpochMs = System.currentTimeMillis()
        voiceManager.ttsStreamReset()
    }

    fun speakChunk(text: String, flush: Boolean = false) {
        if (flush) {
            voiceManager.ttsStreamAppend(text)
            voiceManager.ttsStreamFlush()
        } else {
            voiceManager.ttsStreamAppend(text)
        }
    }

    fun interruptSpeaking() {
        voiceManager.stopSpeaking()
    }

    /** Start the provider-backed PCM duplex path after a provider is selected. */
    fun startConfiguredRealtimeDuplex(systemPrompt: String, voiceId: String = "alloy") {
        val requested = VoicePreferencesStore.load(this).cloudVoiceProvider
        val provider = RealtimeProviderFactory.create(requested)
        if (provider === LocalVoicePipeline) {
            Log.i(TAG, "AIRI REALTIME_CONFIGURED_LOCAL")
            return
        }
        val cloudProvider = when (provider) {
            is com.airi.assistant.voice.realtime.GeminiLiveProvider -> CloudProvider.GEMINI
            is com.airi.assistant.voice.realtime.OpenAIRealtimeProvider -> CloudProvider.OPENAI
            else -> null
        }
        val apiKey = cloudProvider?.let { SecureApiKeyStore(applicationContext).getKey(it) }
        if (apiKey.isNullOrBlank()) {
            val reason = "missing_${requested.trim().lowercase()}_realtime_key"
            Log.w(TAG, "AIRI REALTIME_CONFIGURED_REJECTED reason=$reason")
            listenRequestedByUser = false
            abandonAudioFocus()
            ExecutionTraceGateway.emit("Realtime voice is not configured", com.airi.assistant.ui.activity.ActivityCategory.VOICE)
            return
        }
        realtimeProvider = provider
        startRealtimeDuplex(systemPrompt, apiKey, voiceId)
    }

    fun startRealtimeDuplex(systemPrompt: String, apiKey: String, voiceId: String = "alloy") {
        if (realtimeProvider === LocalVoicePipeline || !realtimeProvider.supportsBidirectionalStreaming) {
            Log.w(TAG, "AIRI REALTIME_DUPLEX_REJECTED provider=${realtimeProvider.name}")
            return
        }
        stopRealtimeDuplex()
        voiceManager.stopAll()
        val sessionId = session.beginSession()
        val transport = RealtimeAudioTransport(
            context = applicationContext,
            provider = realtimeProvider,
            parentScope = serviceScope,
            listener = object : RealtimeAudioTransport.Listener {
                override fun onStarted() {
                    updateNotification(VoicePipelineState.LISTENING)
                    Log.i(TAG, "AIRI REALTIME_DUPLEX_READY session=$sessionId")
                }

                override fun onOutputFrame(samples: Int) {
                    if (session.isActive(sessionId)) {
                        session.onRealtimeResponseStarted()
                        updateNotification(VoicePipelineState.STREAMING_RESPONSE)
                    }
                }

                override fun onTranscript(text: String) {
                    Log.d(TAG, "AIRI REALTIME_TRANSCRIPT chars=${text.length}")
                }

                override fun onResponseText(text: String) {
                    if (session.isActive(sessionId)) {
                        // Realtime providers already render audio through AudioTrack.
                        // Their text event is assistant output and must never be
                        // published on the user-input transcript bus, otherwise
                        // ChatViewModel would submit the assistant reply again.
                        session.emitAssistantTranscript(text)
                    }
                }

                override fun onError(reason: String) {
                    Log.w(TAG, "AIRI REALTIME_DUPLEX_ERROR reason=$reason")
                    if (session.isActive(sessionId)) session.endSession()
                }

                override fun onStopped() {
                    if (session.isActive(sessionId)) session.endSession()
                    updateNotification(VoicePipelineState.IDLE)
                }
            }
        )
        realtimeTransport = transport
        serviceScope.launch {
            val result = transport.start(systemPrompt, apiKey, voiceId)
            if (result !is RealtimeVoiceProvider.ConnectResult.Success) {
                if (session.isActive(sessionId)) session.endSession()
                if (realtimeTransport === transport) realtimeTransport = null
            }
        }
    }

    fun stopRealtimeDuplex() {
        val transport = realtimeTransport ?: return
        realtimeTransport = null
        serviceScope.launch { transport.stopAndDisconnect() }
    }

    // ── Audio focus helpers ───────────────────────────────────────────────────

    /**
     * Request transient audio focus from the system.
     * Returns true if granted, false if denied.
     */
    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: return true  // defensive: proceed if not built
            audioManager.requestAudioFocus(req)
        } else {
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }.onFailure { e -> Log.w(TAG, "abandonAudioFocus failed: ${e.message}") }
    }

    // ── VoiceListener ─────────────────────────────────────────────────────────

    private fun buildVoiceListener(): VoiceManager.VoiceListener =
        object : VoiceManager.VoiceListener {

            override fun onWakeWordDetected() {
                Log.d(TAG, "onWakeWordDetected → LISTENING")
                val sessionId = if (session.currentSessionId == 0L) {
                    VoiceHealthTracker.sessionStarted()
                    session.beginSession()
                } else {
                    session.onListenStart()
                    session.currentSessionId
                }
                sttStartEpochMs = System.currentTimeMillis()
                updateNotification(VoicePipelineState.LISTENING)
                Log.i(TAG, "AIRI VOICE_WAKE_SESSION session=$sessionId")
            }

            override fun onListeningStarted() {
                sttStartEpochMs = System.currentTimeMillis()
            }

            override fun onListeningStarted(generation: Long) {
                activeSttGeneration = generation
                sttStartEpochMs = System.currentTimeMillis()
            }

            override fun onPartialResult(text: String) {
                session.onPartialTranscript(text)
            }

            override fun onPartialResult(text: String, generation: Long) {
                if (generation != activeSttGeneration) {
                    Log.w(TAG, "AIRI VOICE_STALE_PARTIAL_IGNORED generation=$generation active=$activeSttGeneration")
                    return
                }
                session.onPartialTranscript(text)
            }

            override fun onSpeechResult(text: String) {
                onSpeechResult(text, activeSttGeneration)
            }

            override fun onSpeechResult(text: String, generation: Long) {
                if (generation != activeSttGeneration) {
                    Log.w(TAG, "AIRI VOICE_STALE_RESULT_IGNORED generation=$generation active=$activeSttGeneration")
                    return
                }
                val sessionId = session.currentSessionId
                if (sessionId == 0L || !session.isActive(sessionId)) {
                    Log.w(TAG, "AIRI VOICE_STALE_RESULT_IGNORED reason=no_active_session")
                    return
                }
                val sttMs = System.currentTimeMillis() - sttStartEpochMs
                session.recordSttLatency(sttMs)
                if (!session.onSpeechResult(text, sessionId)) {
                    Log.w(TAG, "AIRI VOICE_STALE_RESULT_IGNORED session=$sessionId")
                    return
                }
                thinkingStartEpochMs = System.currentTimeMillis()
                updateNotification(VoicePipelineState.THINKING)
                Log.d(TAG, "AIRI STT_RESULT sttLatency=${sttMs}ms chars=${text.length}")

                routeJob?.cancel()
                routeJob = serviceScope.launch {
                    if (!session.isActive(sessionId)) {
                        Log.i(TAG, "AIRI VOICE_ROUTE_CANCELLED session=$sessionId")
                        return@launch
                    }
                    when (val r = voiceAgentRouter.route(text, sessionId)) {
                        is VoiceAgentRouter.VoiceRouteResult.Handled -> {
                            if (!session.isActive(sessionId)) {
                                Log.i(TAG, "AIRI VOICE_SPEAK_CANCELLED session=$sessionId")
                                return@launch
                            }
                            val ttfb = System.currentTimeMillis() - thinkingStartEpochMs
                            session.recordTtsFirstByteLatency(ttfb)
                            session.onResponseStreaming(ttfb)
                            updateNotification(VoicePipelineState.STREAMING_RESPONSE)
                            voiceManager.speak(r.spokenText)
                            Log.i(TAG, "AIRI VOICE_AGENT_SPOKE agent=${r.agentId} ttfb=${ttfb}ms")
                        }
                        VoiceAgentRouter.VoiceRouteResult.Fallback -> {
                            if (!session.isActive(sessionId)) {
                                Log.i(TAG, "AIRI VOICE_FALLBACK_CANCELLED session=$sessionId")
                                return@launch
                            }
                            Log.d(TAG, "AIRI VOICE_LLM_DISPATCH chars=${text.length}")
                            session.emitPendingTranscript(text)
                            ServiceLocator.voiceTranscriptBus.emit(text)
                        }
                    }
                }
            }

            override fun onSpeakingStarted() {
                val ttfb = System.currentTimeMillis() - thinkingStartEpochMs
                session.recordTtsFirstByteLatency(ttfb)
                session.onResponseStreaming(ttfb)
                updateNotification(VoicePipelineState.STREAMING_RESPONSE)
                Log.d(TAG, "AIRI TTS_START ttfb=${ttfb}ms")
            }

            override fun onSpeakingDone() {
                if (session.currentSessionId == 0L) {
                    Log.i(TAG, "AIRI VOICE_TTS_DONE_IGNORED reason=no_active_session")
                    return
                }
                session.onTurnComplete()
                VoiceHealthTracker.sessionCompleted()
                updateNotification(VoicePipelineState.IDLE)
                Log.d(TAG, "AIRI TTS_DONE — auto-rearming STT")
                requestListen()
            }

            override fun onVadInterrupted() {
                val interruptStart = System.currentTimeMillis()
                session.onBargeIn()
                updateNotification(VoicePipelineState.INTERRUPTED)
                interruptController.onVadSpeechDetected()
                ExecutionTraceGateway.emit("Barge-in via VAD", com.airi.assistant.ui.activity.ActivityCategory.VOICE)
                serviceScope.launch {
                    val interruptMs = System.currentTimeMillis() - interruptStart
                    session.recordInterruptionLatency(interruptMs)
                }
            }

            override fun onListeningStopped() {
                // Driven by onSpeechResult or requestStop — no state transition needed here.
            }

            override fun onError(error: String) {
                Log.w(TAG, "VoiceManager error: $error")
                VoiceHealthTracker.error(error)
                VoiceFeedbackPlayer.playError(applicationContext)
                val shouldRecover = session.onError(error)
                if (shouldRecover && listenRequestedByUser) {
                    recoveryJob?.cancel()
                    recoveryJob = serviceScope.launch {
                        delay(1_500L)
                        if (!listenRequestedByUser) return@launch
                        Log.i(TAG, "Recovery attempt ${session.recoveryAttempts}")
                        voiceManager.stopAll()
                        delay(200L)
                        // Recovery must invalidate the old STT callbacks before reopening
                        // AudioRecord.  Calling onRecoverySuccess() alone leaves the state
                        // at LISTENING, so requestListen() would intentionally no-op.
                        session.endSession()
                        requestListen()
                    }
                } else {
                    Log.e(TAG, "Max recovery attempts exhausted — voice IDLE")
                    updateNotification(VoicePipelineState.IDLE)
                }
            }
        }

    // ── Notification management ───────────────────────────────────────────────

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

        val stopIntent = Intent(this, LiveVoiceService::class.java).apply { action = ACTION_STOP }
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

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG             = "LiveVoiceService"
        private const val CHANNEL_ID      = "airi_voice_channel"
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
