package com.airi.assistant.voice.realtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAIRealtimeProvider — production RealtimeVoiceProvider for OpenAI Realtime API.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PROTOCOL (OpenAI Realtime API v1)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Endpoint: wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview
 *   Auth:     Authorization: Bearer {API_KEY}
 *             OpenAI-Beta: realtime=v1
 *
 *   Client events sent:
 *     session.update              — configure voice, instructions, VAD, modalities
 *     input_audio_buffer.append  — stream PCM-16 base64 chunks
 *     input_audio_buffer.commit  — signal end of user turn (commitAudioTurn)
 *     response.create            — trigger model response
 *     response.cancel            — interrupt model output (barge-in)
 *
 *   Server events received:
 *     session.created                    — ready
 *     response.audio.delta              — audio chunk (base64 PCM-16)
 *     response.audio_transcript.delta   — rolling transcript
 *     response.text.delta               — text response delta
 *     response.audio.done               — audio stream complete
 *     response.done                     — full turn complete
 *     error                             — error
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * AUDIO FORMAT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Input:  PCM-16, 24kHz mono  (OpenAI requires 24kHz input)
 *   Output: PCM-16, 24kHz mono
 *
 *   Note: Vosk/FullDuplexVadEngine produces 16kHz. Resample before sending
 *   when using server VAD mode, or use client VAD (local) which is format-flexible.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   val provider = OpenAIRealtimeProvider()
 *   provider.connect(systemPrompt = "You are AIRI...", apiKey = "sk-...", voiceId = "nova")
 *   liveVoiceService.binder.setRealtimeProvider(provider)
 */
class OpenAIRealtimeProvider(
    private val modelId:  String  = "gpt-4o-realtime-preview-2024-12-17",
    private val vadMode:  VadMode = VadMode.SERVER
) : RealtimeVoiceProvider {

    private val TAG = "OpenAIRealtimeProvider"

    enum class VadMode { SERVER, NONE }

    /** API key stored at provider selection time; used by LiveVoiceService.restoreProviderPreference(). */
    var storedApiKey: String = ""

    // ── Interface properties ───────────────────────────────────────────────────

    override val name: String = "OpenAI Realtime ($modelId)"
    override val endpointDescription: String =
        "wss://api.openai.com/v1/realtime?model=$modelId"
    override val supportsBidirectionalStreaming: Boolean = true
    override val expectedLatencyMs: IntRange = 200..500

    // ── Channels → hot Flows ──────────────────────────────────────────────────

    private val audioOutChannel     = Channel<ShortArray>(capacity = Channel.UNLIMITED)
    private val transcriptChannel   = Channel<String>(capacity = Channel.UNLIMITED)
    private val responseTextChannel = Channel<String>(capacity = Channel.UNLIMITED)

    override val audioResponseFlow: Flow<ShortArray> = audioOutChannel.receiveAsFlow()
    override val transcriptFlow:    Flow<String>     = transcriptChannel.receiveAsFlow()
    override val responseTextFlow:  Flow<String>     = responseTextChannel.receiveAsFlow()

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = AtomicBoolean(false)
    override val isConnected: Boolean get() = _connected.get()

    /** Extended: live StateFlow of connection phase for observability. */
    private val _phase = MutableStateFlow(Phase.DISCONNECTED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Extended: round-trip latency from connect() call to session.created. */
    @Volatile var connectLatencyMs: Long = 0L
        private set

    enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    // ── Internal ───────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var connectTimeMs: Long = 0L

    // Stored at connect() for use in session.update
    @Volatile private var sessionInstructions: String = ""
    @Volatile private var sessionVoice:        String = "nova"

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun connect(
        systemPrompt: String,
        apiKey:       String,
        voiceId:      String
    ): RealtimeVoiceProvider.ConnectResult {
        if (_connected.get()) return RealtimeVoiceProvider.ConnectResult.Success
        _phase.value = Phase.CONNECTING
        sessionInstructions = systemPrompt
        sessionVoice        = voiceId.ifBlank { "nova" }

        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$modelId")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta",   "realtime=v1")
            .build()

        connectTimeMs = System.currentTimeMillis()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                webSocket = ws
                _connected.set(true)
                _phase.value = Phase.CONNECTED
                Log.i(TAG, "AIRI OPENAI_REALTIME_CONNECTED latency=${System.currentTimeMillis() - connectTimeMs}ms")
                sendSessionUpdate(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val code = response?.code ?: -1
                val msg  = response?.let { "HTTP $code" } ?: (t.message ?: "Unknown")
                Log.e(TAG, "WebSocket failure: $msg")
                _connected.set(false)
                _phase.value = Phase.ERROR
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                _connected.set(false)
                _phase.value = Phase.DISCONNECTED
            }
        }

        webSocket = httpClient.newWebSocket(request, listener)
        return RealtimeVoiceProvider.ConnectResult.Success
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "AIRI disconnect")
        webSocket = null
        _connected.set(false)
        _phase.value = Phase.DISCONNECTED
        Log.i(TAG, "OpenAI Realtime disconnected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio I/O
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun sendAudioChunk(pcm16: ShortArray) {
        val ws = webSocket ?: return
        if (!_connected.get()) return
        val bytes = shortsToBytes(pcm16)
        val b64   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        ws.send(JSONObject().apply {
            put("type",  "input_audio_buffer.append")
            put("audio", b64)
        }.toString())
    }

    /**
     * Commit the audio buffer and trigger a response.
     * In SERVER VAD mode this is typically automatic — call for NONE VAD mode.
     */
    override suspend fun commitAudioTurn() {
        val ws = webSocket ?: return
        ws.send(JSONObject().apply { put("type", "input_audio_buffer.commit") }.toString())
        ws.send(JSONObject().apply {
            put("type",     "response.create")
            put("response", JSONObject().apply {
                put("modalities", JSONArray().apply { put("audio"); put("text") })
            })
        }.toString())
    }

    override suspend fun interrupt() {
        webSocket?.send(JSONObject().apply {
            put("type",     "response.cancel")
            put("event_id", "cancel_${UUID.randomUUID()}")
        }.toString())
        Log.d(TAG, "OPENAI_REALTIME_INTERRUPT sent")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — session setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendSessionUpdate(ws: WebSocket) {
        val turnDetection = if (vadMode == VadMode.SERVER) {
            JSONObject().apply {
                put("type",              "server_vad")
                put("threshold",         0.5)
                put("prefix_padding_ms",  300)
                put("silence_duration_ms", 200)
            }
        } else JSONObject.NULL

        val update = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities",          JSONArray().apply { put("audio"); put("text") })
                put("instructions",        sessionInstructions)
                put("voice",               sessionVoice)
                put("input_audio_format",  "pcm16")
                put("output_audio_format", "pcm16")
                put("temperature",         0.7)
                put("max_response_output_tokens", "inf")
                if (vadMode == VadMode.SERVER) put("turn_detection", turnDetection)
            })
        }.toString()
        ws.send(update)
        Log.d(TAG, "OpenAI Realtime session.update sent voice=$sessionVoice vad=$vadMode")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — server event handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleServerEvent(text: String) {
        try {
            val json      = JSONObject(text)
            val eventType = json.optString("type")
            when (eventType) {
                "session.created" -> {
                    connectLatencyMs = System.currentTimeMillis() - connectTimeMs
                    Log.i(TAG, "OpenAI Realtime session created latency=${connectLatencyMs}ms")
                }
                "response.audio.delta" -> {
                    val b64 = json.optString("delta")
                    if (b64.isNotBlank()) {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                        audioOutChannel.trySend(bytesToShorts(bytes))
                    }
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotBlank()) transcriptChannel.trySend(delta)
                }
                "response.text.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotBlank()) responseTextChannel.trySend(delta)
                }
                "response.audio.done" -> Log.d(TAG, "OPENAI_AUDIO_DONE")
                "response.done"       -> Log.d(TAG, "OPENAI_RESPONSE_DONE")
                "error" -> {
                    val errObj = json.optJSONObject("error")
                    val msg    = errObj?.optString("message") ?: "Unknown error"
                    val code   = errObj?.optString("code") ?: ""
                    Log.e(TAG, "Realtime API error code=$code: $msg")
                    if (code == "session_expired") {
                        _connected.set(false)
                        _phase.value = Phase.ERROR
                    }
                }
                else -> Log.v(TAG, "Unhandled event: $eventType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleServerEvent error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversion helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return shorts
    }

    /** Clean up — call when the provider is no longer needed. */
    fun destroy() {
        scope.cancel()
        webSocket?.cancel()
        webSocket = null
        audioOutChannel.close()
        transcriptChannel.close()
        responseTextChannel.close()
        Log.i(TAG, "OpenAIRealtimeProvider destroyed")
    }
}
