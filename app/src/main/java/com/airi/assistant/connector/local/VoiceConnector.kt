package com.airi.assistant.connector.local

import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice / multimodal connector — façade over the on-device mtmd / Vosk
 * pipeline. Implementation is delegated to a [VoiceBackend] which the
 * caller supplies during registration. This keeps the connector layer
 * decoupled from the specific Vosk / mtmd / Porcupine wiring living in
 * the rest of the app, and keeps mtmd intact (per project rules — the
 * audio layer must never be removed).
 *
 * If no backend is supplied (lab/test builds) the connector reports
 * `not_connected` and still appears in the UI so users can see what
 * would be available.
 */
class VoiceConnector(
    private val backend: VoiceBackend? = null,
) : Connector {

    override val id = "voice_mtmd"
    override val name = "Voice (mtmd)"
    override val description = "On-device speech-to-text and audio understanding."
    override val type = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(
            connected = backend != null,
            healthy = backend != null,
            statusLine = if (backend != null) "Backend ready" else "No backend wired",
        )
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("voice", "audio", "mtmd", "stt"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        // Capture the property into a local val so Kotlin's smart-cast can
        // narrow the type from `VoiceBackend?` to `VoiceBackend` after the
        // null-check below. Smart-cast does not propagate from open class
        // properties (they could in principle be mutated between reads).
        val b = backend
        if (b == null) {
            _state.value = _state.value.copy(
                connected = false, healthy = false,
                statusLine = "No backend wired",
                errorMessage = "VoiceBackend not provided to this connector",
                lastUpdatedMs = System.currentTimeMillis(),
            )
            return _state.value
        }
        val ok = runCatching { b.warmUp() }.getOrDefault(false)
        _state.value = ConnectorState(
            connected = ok, healthy = ok,
            statusLine = if (ok) "Voice backend ready" else "Voice backend failed warm-up",
            lastUpdatedMs = System.currentTimeMillis(),
            errorMessage = if (ok) null else "Backend warm-up returned false",
        )
        return _state.value
    }

    override suspend fun disconnect() {
        backend?.let { runCatching { it.release() } }
        _state.value = _state.value.copy(
            connected = false, healthy = false,
            statusLine = "Released",
            lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        val b = backend ?: return ConnectorOutput.Failure(
            code = "not_connected",
            message = "Voice backend is not wired",
            retryable = false,
        )
        return when (input.action) {
            "transcribe" -> {
                val audio = input.binary ?: return ConnectorOutput.Failure(
                    code = "bad_input", message = "Missing audio bytes",
                )
                runCatching { b.transcribe(audio) }
                    .map { ConnectorOutput.Success(text = it) as ConnectorOutput }
                    .getOrElse {
                        ConnectorOutput.Failure(
                            code = "transcribe_failed",
                            message = it.message ?: "transcription threw",
                            retryable = true,
                        )
                    }
            }
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "VoiceConnector does not handle '${input.action}'",
            )
        }
    }

    /**
     * Voice backend contract. Implemented by the existing on-device
     * voice service (Vosk wrapper, mtmd bridge, etc.) and registered
     * via [com.airi.assistant.connector.ConnectorBootstrap].
     */
    interface VoiceBackend {
        /** Cheap warm-up — returns true if the backend is ready to
         *  transcribe. Called from [connect]. */
        suspend fun warmUp(): Boolean

        /** Convert raw PCM/WAV audio bytes to text. */
        suspend fun transcribe(audio: ByteArray): String

        /** Release any held native resources. */
        suspend fun release()
    }
}
