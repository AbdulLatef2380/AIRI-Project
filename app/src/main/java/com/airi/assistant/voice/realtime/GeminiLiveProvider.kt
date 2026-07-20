package com.airi.assistant.voice.realtime

import android.util.Log
import com.airi.assistant.voice.realtime.RealtimeVoiceProvider
import kotlinx.coroutines.*
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
    private val apiKey: String,
    private val model:  String = "gemini-2.0-flash-exp"
) : RealtimeVoiceProvider {

    private val TAG = "AIRI_GeminiLive"
    private val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BiDiGenerateContent"

    private val audioOutChannel   = Channel<ShortArray>(Channel.BUFFERED)
    private val transcriptChannel = Channel<String>(Channel.BUFFERED)
    private val responseTextChannel = Channel<String>(Channel.BUFFERED)

    override val audioResponseFlow:  Flow<ShortArray> = audioOutChannel.receiveAsFlow()
    override val transcriptFlow:     Flow<String>     = transcriptChannel.receiveAsFlow()
    override val responseTextFlow:   Flow<String>     = responseTextChannel.receiveAsFlow()

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = AtomicBoolean(false)
    override val isConnected: Boolean get() = _connected.get()

    /** Extended: live StateFlow of connection phase for observability. */
    private val _phase = MutableStateFlow(GeminiPhase.DISCONNECTED)
    val phase: StateFlow<GeminiPhase> = _phase.asStateFlow()

    /** Extended: round-trip latency from connect() call to onOpen(). */
    @Volatile var connectLatencyMs: Long = 0L
        private set

    enum class GeminiPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    // ── Internal ───────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var connectTimeMs: Long = 0L

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun connect() {
        if (_connected.get()) return
        _phase.value = GeminiPhase.CONNECTING
        connectTimeMs = System.currentTimeMillis()

        val url = "$BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, GeminiWebSocketListener())
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connected.set(false)
        _phase.value = GeminiPhase.DISCONNECTED
    }

    override suspend fun sendAudio(pcm16: ShortArray) {
        if (!_connected.get()) return
        // Implementation of audio streaming to Gemini
    }

    override suspend fun sendText(text: String) {
        if (!_connected.get()) return
        // Implementation of text injection
    }

    override suspend fun interrupt() {
        // Gemini Live handles interruption via specific control messages
    }

    private inner class GeminiWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.set(true)
            _phase.value = GeminiPhase.CONNECTED
            connectLatencyMs = System.currentTimeMillis() - connectTimeMs
            Log.i(TAG, "Connected to Gemini Live in ${connectLatencyMs}ms")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Parse Gemini's JSON responses (audio frames, transcripts)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.set(false)
            _phase.value = GeminiPhase.ERROR
            Log.e(TAG, "Gemini WebSocket failure: ${t.message}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _connected.set(false)
            _phase.value = GeminiPhase.DISCONNECTED
        }
    }
}
