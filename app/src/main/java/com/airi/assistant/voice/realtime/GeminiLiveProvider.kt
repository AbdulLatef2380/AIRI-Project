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
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeminiLiveProvider — production RealtimeVoiceProvider for Google Gemini Live API.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PROTOCOL (Gemini Live v1beta / BidiGenerateContent)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Endpoint:
 *     wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta
 *     .GenerativeService.BidiGenerateContent?key={API_KEY}
 *
 *   Session flow:
 *     1. Connect → send BidiGenerateContentSetup (model, systemPrompt, voice)
 *     2. Stream mic audio → send BidiGenerateContentRealtimeInput (base64 PCM-16)
 *     3. Receive → BidiGenerateContentServerContent (audio chunks + transcripts)
 *     4. turnComplete=true in server message → call [commitAudioTurn] equivalent
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * AUDIO FORMAT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Input:  PCM-16, 16kHz mono  (matches Vosk + FullDuplexVadEngine output)
 *   Output: PCM-16, 24kHz mono  (resample to device rate before playback)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   val provider = GeminiLiveProvider()
 *   provider.connect(systemPrompt = "You are AIRI...", apiKey = "AIza...", voiceId = "Aoede")
 *   liveVoiceService.binder.setRealtimeProvider(provider)
 *
 *   Swap back to local: liveVoiceService.binder.setRealtimeProvider(null)
 */
class GeminiLiveProvider(
    private val modelId:   String = "gemini-2.0-flash-live-001",
    private val voiceName: String = "Aoede"
) : RealtimeVoiceProvider {

    private val TAG = "GeminiLiveProvider"

    // ── Interface properties ───────────────────────────────────────────────────

    /** API key stored at provider selection time; used by LiveVoiceService.restoreProviderPreference(). */
    var storedApiKey: String = ""

        override val name: String = "Gemini Live ($modelId)"
    override val endpointDescription: String =
        "wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent"
    override val supportsBidirectionalStreaming: Boolean = true
    override val expectedLatencyMs: IntRange = 150..400

    // ── Channels → hot Flows ──────────────────────────────────────────────────

    private val audioOutChannel      = Channel<ShortArray>(capacity = Channel.UNLIMITED)
    private val transcriptChannel    = Channel<String>(capacity = Channel.UNLIMITED)
    private val responseTextChannel  = Channel<String>(capacity = Channel.UNLIMITED)

    override val audioResponseFlow:  Flow<ShortArray> = audioOutChannel.receiveAsFlow()
    override val transcriptFlow:     Flow<String>     = transcriptChannel.receiveAsFlow()
    override val responseTextFlow:   Flow<String>     = responseTextChannel.receiveAsFlow()

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = AtomicBoolean(false)
    override val isConnected: Boolean get() = _connected.get()

    /** Extended: live StateFlow of connection phase for observability. */
    private val _phase = MutableStateFlow(GeminiPhase.DISCONNECTED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

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

    // ─────────────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun connect(
        systemPrompt: String,
        apiKey:       String,
        voiceId:      String
    ): RealtimeVoiceProvider.ConnectResult {
        if (_connected.get()) return RealtimeVoiceProvider.ConnectResult.Success
        _phase.value = GeminiPhase.CONNECTING

        val resolvedVoice = voiceId.ifBlank { voiceName }
        val endpoint = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=$apiKey"

        connectTimeMs = System.currentTimeMillis()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                webSocket = ws
                _connected.set(true)
                _phase.value = GeminiPhase.CONNECTED
                connectLatencyMs = System.currentTimeMillis() - connectTimeMs
                Log.i(TAG, "AIRI_PROOF GEMINI_LIVE_CONNECTED latency=${connectLatencyMs}ms")
                sendSetup(ws, systemPrompt, resolvedVoice)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleAudioFrame(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connected.set(false)
                _phase.value = GeminiPhase.ERROR
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                _connected.set(false)
                _phase.value = GeminiPhase.DISCONNECTED
            }
        }

        webSocket = httpClient.newWebSocket(
            Request.Builder().url(endpoint).build(), listener
        )
        return RealtimeVoiceProvider.ConnectResult.Success
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "AIRI disconnect")
        webSocket = null
        _connected.set(false)
        _phase.value = GeminiPhase.DISCONNECTED
        Log.i(TAG, "Gemini Live disconnected")
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
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }.toString())
    }

    override suspend fun commitAudioTurn() {
        webSocket?.send(JSONObject().apply {
            put("clientContent", JSONObject().apply { put("turnComplete", true) })
        }.toString())
    }

    override suspend fun interrupt() {
        webSocket?.send(JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turnComplete", false)
                put("activityEnd", JSONObject())
            })
        }.toString())
        Log.d(TAG, "GEMINI_LIVE_INTERRUPT sent")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendSetup(ws: WebSocket, systemPrompt: String, voice: String) {
        ws.send(JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$modelId")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voice)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
            })
        }.toString())
        Log.d(TAG, "Gemini Live setup sent model=$modelId voice=$voice")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — server message parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleServerMessage(text: String) {
        try {
            val json          = JSONObject(text)
            val serverContent = json.optJSONObject("serverContent") ?: return
            val modelTurn     = serverContent.optJSONObject("modelTurn")
            val parts         = modelTurn?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part       = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val b64   = inlineData.optString("data")
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                        audioOutChannel.trySend(bytesToShorts(bytes))
                    }
                    val partText = part.optString("text")
                    if (partText.isNotBlank()) {
                        transcriptChannel.trySend(partText)
                        responseTextChannel.trySend(partText)
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                Log.d(TAG, "GEMINI_LIVE_TURN_COMPLETE")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleServerMessage error: ${e.message}")
        }
    }

    private fun handleAudioFrame(bytes: ByteArray) {
        audioOutChannel.trySend(bytesToShorts(bytes))
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
        Log.i(TAG, "GeminiLiveProvider destroyed")
    }
}
