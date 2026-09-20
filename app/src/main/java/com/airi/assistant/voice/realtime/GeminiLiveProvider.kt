package com.airi.assistant.voice.realtime

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URLEncoder

/**
 * Gemini Live API provider over the bidirectional WebSocket protocol.
 *
 * Transport contract:
 *  - input: little-endian PCM-16 mono, normally 16 kHz
 *  - output: little-endian PCM-16 mono, normally 24 kHz
 *  - setup is sent as the first client message
 *  - microphone frames are sent as realtimeInput.audio.inlineData
 *  - serverContent.modelTurn.parts[].inlineData contains audio
 *  - input/output transcription deltas are emitted independently
 *
 * The WebSocket callback thread never touches Compose state. It only parses
 * messages and offers values to channels, which are collected by the caller.
 */
class GeminiLiveProvider(
    // Gemini 2.0 Flash Live was shut down by Google; 3.8 Live is the
    // currently documented Live API replacement.
    private val model: String = "gemini-3.8-live"
) : RealtimeVoiceProvider {

    private val tag = "AIRI_GeminiLive"
    private val endpoint =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** API key stored at provider selection time by the voice service. */
    @Volatile var storedApiKey: String = ""

    private val audioOutChannel = Channel<ShortArray>(Channel.UNLIMITED)
    private val transcriptChannel = Channel<String>(Channel.UNLIMITED)
    private val responseTextChannel = Channel<String>(Channel.UNLIMITED)

    override val audioResponseFlow: Flow<ShortArray> = audioOutChannel.receiveAsFlow()
    override val transcriptFlow: Flow<String> = transcriptChannel.receiveAsFlow()
    override val responseTextFlow: Flow<String> = responseTextChannel.receiveAsFlow()

    override val name: String = "Gemini Live ($model)"
    override val endpointDescription: String = "$endpoint?key=***"
    override val supportsBidirectionalStreaming: Boolean = true
    override val expectedLatencyMs: IntRange = 300..600
    override val isConnected: Boolean get() = connected.get()

    private val connected = AtomicBoolean(false)
    private val phaseState = MutableStateFlow(GeminiPhase.DISCONNECTED)
    val phase: StateFlow<GeminiPhase> = phaseState.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var setupResult: CompletableDeferred<SetupResult>? = null
    @Volatile private var apiKey: String = ""
    @Volatile private var turnText = StringBuilder()

    enum class GeminiPhase { DISCONNECTED, CONNECTING, SETUP, CONNECTED, ERROR }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private sealed class SetupResult {
        data object Success : SetupResult()
        data class Failure(val reason: String, val code: Int = -1) : SetupResult()
    }

    override suspend fun connect(
        systemPrompt: String,
        apiKey: String,
        voiceId: String
    ): RealtimeVoiceProvider.ConnectResult {
        require(apiKey.isNotBlank()) { "Gemini Live API key must not be blank" }
        if (connected.get()) return RealtimeVoiceProvider.ConnectResult.Success

        disconnect()
        this.apiKey = apiKey.trim()
        phaseState.value = GeminiPhase.CONNECTING
        val ready = CompletableDeferred<SetupResult>()
        setupResult = ready

        val encodedKey = URLEncoder.encode(this.apiKey, Charsets.UTF_8.name())
        val request = Request.Builder().url("$endpoint?key=$encodedKey").build()
        val socket = httpClient.newWebSocket(request, GeminiWebSocketListener(systemPrompt, voiceId))
        webSocket = socket

        val result = withTimeoutOrNull(15_000L) { ready.await() }
            ?: SetupResult.Failure("Timed out waiting for Gemini Live setupComplete")

        return when (result) {
            SetupResult.Success -> RealtimeVoiceProvider.ConnectResult.Success
            is SetupResult.Failure -> {
                phaseState.value = GeminiPhase.ERROR
                socket.close(1000, "setup failed")
                webSocket = null
                RealtimeVoiceProvider.ConnectResult.Failure(result.reason, result.code)
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        setupResult?.complete(SetupResult.Failure("Disconnected during setup"))
        setupResult = null
        connected.set(false)
        phaseState.value = GeminiPhase.DISCONNECTED
        webSocket?.close(1000, "AIRI disconnect")
        webSocket = null
        turnText = StringBuilder()
    }

    /** Queues one PCM frame as a Gemini Live realtime audio message. */
    override suspend fun sendAudioChunk(pcm16: ShortArray) {
        if (pcm16.isEmpty() || !connected.get()) return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", Base64.encodeToString(shortsToBytes(pcm16), Base64.NO_WRAP))
                })
            })
        }
        if (!(webSocket?.send(payload.toString()) ?: false)) {
            Log.w(tag, "GEMINI_AUDIO_SEND_FAILED")
        }
    }

    /**
     * Gemini normally detects end-of-speech itself. This explicit marker is
     * useful when the client controls VAD and is required by the Live API when
     * automatic activity detection is enabled and the microphone is stopped.
     */
    override suspend fun commitAudioTurn() {
        if (!connected.get()) return
        webSocket?.send(
            JSONObject().apply {
                put("realtimeInput", JSONObject().apply { put("audioStreamEnd", true) })
            }.toString()
        )
    }

    /** Interrupts generation and clears queued playback on the consumer side. */
    override suspend fun interrupt() {
        if (!connected.get()) return
        // A clientContent message interrupts current generation according to
        // Gemini Live. An empty turn is not appended; it only signals activity.
        webSocket?.send(
            JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turnComplete", true)
                })
            }.toString()
        )
        turnText = StringBuilder()
        Log.d(tag, "GEMINI_LIVE_INTERRUPT_SENT")
    }

    private inner class GeminiWebSocketListener(
        private val systemPrompt: String,
        private val voiceId: String
    ) : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (webSocket !== ws) return
            webSocket = ws
            phaseState.value = GeminiPhase.SETUP
            val sent = ws.send(buildSetup(systemPrompt, voiceId).toString())
            if (!sent) failSetup("Failed to send Gemini Live setup")
            Log.i(tag, "GEMINI_LIVE_SOCKET_OPEN model=$model")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (webSocket !== ws) return
            handleServerMessage(text)
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            if (webSocket !== ws) return
            // Gemini Live sends JSON text frames. Preserve this overload so a
            // proxy or future server binary frame cannot silently disappear.
            runCatching { handleServerMessage(bytes.utf8()) }
                .onFailure { Log.w(tag, "Invalid binary server frame: ${it.message}") }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            val code = response?.code ?: -1
            val reason = response?.let { "HTTP ${it.code}: ${it.message}" }
                ?: (t.message ?: "Gemini Live WebSocket failure")
            Log.e(tag, "GEMINI_LIVE_FAILURE code=$code reason=$reason")
            connected.set(false)
            phaseState.value = GeminiPhase.ERROR
            setupResult?.complete(SetupResult.Failure(reason, code))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            connected.set(false)
            phaseState.value = GeminiPhase.DISCONNECTED
            setupResult?.complete(SetupResult.Failure("WebSocket closed: $code $reason", code))
            Log.i(tag, "GEMINI_LIVE_CLOSED code=$code reason=$reason")
        }
    }

    private fun buildSetup(systemPrompt: String, voiceId: String): JSONObject = JSONObject().apply {
        put("setup", JSONObject().apply {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceId.ifBlank { "Puck" })
                        })
                    })
                })
            })
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            }
            // These are independent server streams and may arrive out of order.
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        })
    }

    private fun handleServerMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(tag, "GEMINI_LIVE_INVALID_JSON: ${it.message}")
            return
        }

        json.optJSONObject("setupComplete")?.let {
            connected.set(true)
            phaseState.value = GeminiPhase.CONNECTED
            setupResult?.complete(SetupResult.Success)
            Log.i(tag, "GEMINI_LIVE_SETUP_COMPLETE")
            return@let
        }

        json.optJSONObject("error")?.let { error ->
            val code = error.optInt("code", -1)
            val message = error.optString("message", "Gemini Live server error")
            Log.e(tag, "GEMINI_LIVE_SERVER_ERROR code=$code message=$message")
            setupResult?.complete(SetupResult.Failure(message, code))
            phaseState.value = GeminiPhase.ERROR
            return
        }

        val serverContent = json.optJSONObject("serverContent") ?: return
        serverContent.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { transcriptChannel.trySend(it) }
        serverContent.optJSONObject("interimInputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { transcriptChannel.trySend(it) }
        serverContent.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let {
                turnText.append(it)
                responseTextChannel.trySend(it)
            }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val inline = part.optJSONObject("inlineData")
                if (inline != null) {
                    val mime = inline.optString("mimeType")
                    val data = inline.optString("data")
                    if (data.isNotBlank() && mime.startsWith("audio/")) {
                        runCatching {
                            audioOutChannel.trySend(bytesToShorts(Base64.decode(data, Base64.DEFAULT)))
                        }.onFailure { Log.w(tag, "Invalid Gemini audio chunk: ${it.message}") }
                    }
                }
                part.optString("text").takeIf { it.isNotBlank() }?.let {
                    turnText.append(it)
                    responseTextChannel.trySend(it)
                }
            }
        }

        if (serverContent.optBoolean("interrupted", false)) {
            turnText = StringBuilder()
        }
        if (serverContent.optBoolean("turnComplete", false)) {
            turnText = StringBuilder()
        }
    }

    private fun failSetup(reason: String, code: Int = -1) {
        connected.set(false)
        phaseState.value = GeminiPhase.ERROR
        setupResult?.complete(SetupResult.Failure(reason, code))
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample.toInt() and 0xff).toByte()
            out[index * 2 + 1] = (sample.toInt() ushr 8 and 0xff).toByte()
        }
        return out
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        return ShortArray(count) { index ->
            ((bytes[index * 2].toInt() and 0xff) or
                (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }
    }
}
