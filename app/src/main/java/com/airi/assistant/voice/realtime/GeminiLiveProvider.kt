package com.airi.assistant.voice.realtime

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeminiLiveProvider — implementation of the RealtimeVoiceProvider for Google's
 * Gemini Multimodal Live API (over WebSockets).
 */
class GeminiLiveProvider(
    private val model: String = "gemini-2.0-flash-exp"
) : RealtimeVoiceProvider {

    /** API key stored at provider selection time; used by LiveVoiceService.restoreProviderPreference(). */
    var storedApiKey: String = ""

    private val TAG = "AIRI_GeminiLive"
    private val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BiDiGenerateContent"

    private val audioOutChannel   = Channel<ShortArray>(Channel.BUFFERED)
    private val transcriptChannel = Channel<String>(Channel.BUFFERED)
    private val responseTextChannel = Channel<String>(Channel.BUFFERED)

    override val audioResponseFlow:  Flow<ShortArray> = audioOutChannel.receiveAsFlow()
    override val transcriptFlow:    Flow<String>     = transcriptChannel.receiveAsFlow()
    override val responseTextFlow:  Flow<String>     = responseTextChannel.receiveAsFlow()

    // ── Interface properties ───────────────────────────────────────────────────

    override val name: String = "Gemini Live ($model)"
    override val endpointDescription: String = "$BASE_URL?key=***"
    override val supportsBidirectionalStreaming: Boolean = true
    override val expectedLatencyMs: IntRange = 300..600

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = AtomicBoolean(false)
    private val _phase = MutableStateFlow(GeminiPhase.DISCONNECTED)
    private var webSocket: WebSocket? = null
    private var connectTimeMs: Long = 0
    private var apiKey: String = ""

    enum class GeminiPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR, SETUP }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── connect ───────────────────────────────────────────────────────────────

    override suspend fun connect(
        systemPrompt: String,
        apiKey: String,
        voiceId: String
    ): RealtimeVoiceProvider.ConnectResult {
        this.apiKey = apiKey
        if (_connected.get()) return RealtimeVoiceProvider.ConnectResult.Success
        _phase.value = GeminiPhase.CONNECTING
        connectTimeMs = System.currentTimeMillis()

        val url = "$BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, GeminiWebSocketListener())
        return RealtimeVoiceProvider.ConnectResult.Success
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connected.set(false)
        _phase.value = GeminiPhase.DISCONNECTED
    }

    override suspend fun sendAudioChunk(pcm16: ShortArray) {
        if (!_connected.get()) return
        // Implementation of audio streaming to Gemini
    }

    override suspend fun commitAudioTurn() {
        // Gemini Live handles turn detection server-side automatically; no-op.
    }

    override suspend fun interrupt() {
        // Gemini Live handles interruption via specific control messages
    }

    // ── Internal listener ─────────────────────────────────────────────────────

    private inner class GeminiWebSocketListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            _connected.set(true)
            _phase.value = GeminiPhase.CONNECTED
            Log.i(TAG, "Gemini Live connected")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Parse server events and route to channels
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gemini Live failure: ${t.message}")
            _connected.set(false)
            _phase.value = GeminiPhase.ERROR
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            _connected.set(false)
            _phase.value = GeminiPhase.DISCONNECTED
        }
    }
}
