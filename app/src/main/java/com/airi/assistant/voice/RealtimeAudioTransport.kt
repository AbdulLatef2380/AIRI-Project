package com.airi.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.airi.assistant.voice.realtime.Pcm16Resampler
import com.airi.assistant.voice.realtime.RealtimeVoiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the complete cloud realtime audio transport:
 *
 *   AudioRecord (mic PCM-16 mono)
 *       -> RealtimeVoiceProvider.sendAudioChunk()
 *   RealtimeVoiceProvider.audioResponseFlow
 *       -> AudioTrack (speaker PCM-16 mono)
 *
 * The owner is deliberately separate from Vosk and local TTS. Only one transport
 * instance may own the microphone at a time, and [stop] releases both audio devices
 * synchronously before [stopAndDisconnect] tears down the WebSocket.
 */
class RealtimeAudioTransport(
    private val context: Context,
    private val provider: RealtimeVoiceProvider,
    private val parentScope: CoroutineScope,
    private val listener: Listener = Listener.NONE
) {
    interface Listener {
        fun onStarted() = Unit
        fun onInputFrame(samples: Int) = Unit
        fun onOutputFrame(samples: Int) = Unit
        fun onTranscript(text: String) = Unit
        fun onResponseText(text: String) = Unit
        fun onError(reason: String) = Unit
        fun onStopped() = Unit

        companion object {
            val NONE = object : Listener {}
        }
    }

    enum class State { IDLE, CONNECTING, RUNNING, STOPPING, FAILED }

    private val lock = Any()
    private val stopped = AtomicBoolean(true)
    private val transportScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    @Volatile private var state: State = State.IDLE
    @Volatile private var inputJob: Job? = null
    @Volatile private var outputJob: Job? = null
    @Volatile private var transcriptJob: Job? = null
    @Volatile private var responseTextJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null

    val isRunning: Boolean get() = state == State.RUNNING && !stopped.get()
    val isReady: Boolean get() = provider.supportsBidirectionalStreaming &&
        provider.inputSampleRateHz > 0 && provider.outputSampleRateHz > 0

    /** Connects the provider and starts both PCM directions. */
    suspend fun start(
        systemPrompt: String,
        apiKey: String,
        voiceId: String = "alloy"
    ): RealtimeVoiceProvider.ConnectResult {
        synchronized(lock) {
            if (isRunning) return RealtimeVoiceProvider.ConnectResult.Success
            state = State.CONNECTING
            stopped.set(false)
        }

        if (!isReady) return fail("provider_not_bidirectional")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return fail("missing_record_audio_permission")

        val result = runCatching {
            provider.connect(systemPrompt, apiKey, voiceId)
        }.getOrElse { return fail("provider_connect_failed:${it.javaClass.simpleName}") }
        if (result !is RealtimeVoiceProvider.ConnectResult.Success) {
            stopped.set(true)
            state = State.FAILED
            return result
        }

        val record = createAudioRecord(provider.inputSampleRateHz)
            ?: return fail("audio_record_create_failed")
        val track = createAudioTrack(provider.outputSampleRateHz)
            ?: run {
                releaseRecord(record)
                return fail("audio_track_create_failed")
            }

        synchronized(lock) {
            audioRecord = record
            audioTrack = track
            state = State.RUNNING
        }
        try {
            record.startRecording()
            track.play()
        } catch (t: Throwable) {
            releaseAudioDevices()
            return fail("audio_start_failed:${t.javaClass.simpleName}")
        }

        startOutputCollector()
        startInputCollector()
        startMetadataCollectors()
        listener.onStarted()
        Log.i(TAG, "AIRI REALTIME_DUPLEX_STARTED provider=${provider.name} in=${provider.inputSampleRateHz} out=${provider.outputSampleRateHz}")
        return result
    }

    /** Stops capture/playback immediately; WebSocket teardown is suspendable. */
    fun stop() {
        val wasRunning = !stopped.getAndSet(true)
        state = State.STOPPING
        inputJob?.cancel()
        outputJob?.cancel()
        transcriptJob?.cancel()
        responseTextJob?.cancel()
        inputJob = null
        outputJob = null
        transcriptJob = null
        responseTextJob = null
        releaseAudioDevices()
        if (wasRunning) listener.onStopped()
        state = State.IDLE
    }

    suspend fun stopAndDisconnect() {
        stop()
        runCatching { provider.disconnect() }
            .onFailure { Log.w(TAG, "AIRI REALTIME_DISCONNECT_FAILED ${it.javaClass.simpleName}") }
    }

    private fun startInputCollector() {
        inputJob = transportScope.launch(Dispatchers.IO) {
            // Capture at a stable Android/Vosk-compatible rate, then explicitly
            // resample for providers such as OpenAI Realtime (24 kHz input).
            val samplesPerFrame = (CAPTURE_SAMPLE_RATE_HZ / 50).coerceAtLeast(160)
            val buffer = ShortArray(samplesPerFrame)
            try {
                while (isActive && !stopped.get()) {
                    val record = audioRecord ?: break
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    val captured = if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)
                    provider.sendAudioChunk(
                        Pcm16Resampler.resample(captured, CAPTURE_SAMPLE_RATE_HZ, provider.inputSampleRateHz)
                    )
                    listener.onInputFrame(count)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!stopped.get()) failAsync("audio_input_failed:${t.javaClass.simpleName}")
            }
        }
    }

    private fun startOutputCollector() {
        outputJob = transportScope.launch(Dispatchers.IO) {
            try {
                provider.audioResponseFlow.collect { pcm ->
                    if (stopped.get() || pcm.isEmpty()) return@collect
                    val track = audioTrack ?: return@collect
                    var offset = 0
                    while (offset < pcm.size && isActive && !stopped.get()) {
                        val written = track.write(pcm, offset, pcm.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                    listener.onOutputFrame(pcm.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!stopped.get()) failAsync("audio_output_failed:${t.javaClass.simpleName}")
            }
        }
    }

    private fun startMetadataCollectors() {
        transcriptJob = transportScope.launch {
            provider.transcriptFlow.collect { text ->
                if (!stopped.get() && text.isNotBlank()) listener.onTranscript(text)
            }
        }
        responseTextJob = transportScope.launch {
            provider.responseTextFlow.collect { text ->
                if (!stopped.get() && text.isNotBlank()) listener.onResponseText(text)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(@Suppress("UNUSED_PARAMETER") sampleRate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        return runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(CAPTURE_SAMPLE_RATE_HZ)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes((min * 2).coerceAtLeast(CAPTURE_SAMPLE_RATE_HZ / 5 * 2))
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrElse {
            Log.w(TAG, "AIRI REALTIME_AUDIO_RECORD_CREATE_FAILED ${it.javaClass.simpleName}")
            null
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes((min * 2).coerceAtLeast(sampleRate / 5 * 2))
                .build()
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        }.getOrElse {
            Log.w(TAG, "AIRI REALTIME_AUDIO_TRACK_CREATE_FAILED ${it.javaClass.simpleName}")
            null
        }
    }

    private fun releaseAudioDevices() {
        val record = audioRecord
        audioRecord = null
        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        releaseRecord(record)

        val track = audioTrack
        audioTrack = null
        runCatching {
            if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        }
        runCatching { track?.flush() }
        runCatching { track?.release() }
    }

    private fun releaseRecord(record: AudioRecord?) {
        runCatching { record?.release() }
    }

    private suspend fun fail(reason: String): RealtimeVoiceProvider.ConnectResult.Failure {
        stop()
        runCatching { provider.disconnect() }
        listener.onError(reason)
        return RealtimeVoiceProvider.ConnectResult.Failure(reason)
    }

    private fun failAsync(reason: String) {
        transportScope.launch(Dispatchers.Main) {
            if (!stopped.get()) {
                listener.onError(reason)
                stop()
            }
        }
    }

    fun destroy() {
        stop()
        transportScope.cancel()
    }

    private companion object {
        const val TAG = "AIRI_REALTIME_AUDIO"
        const val CAPTURE_SAMPLE_RATE_HZ = 16_000
    }
}
