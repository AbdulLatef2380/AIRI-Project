package com.airi.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class IncrementalTtsEngine(private val context: Context) {
    private val TAG = "IncrementalTtsEngine"

    enum class TtsState { IDLE, SPEAKING, STOPPED }

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val stopped = AtomicBoolean(false)
    private val scope   = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sentenceChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var playerJob: Job? = null
    private val tokenBuffer = StringBuilder()
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                // Apply user personalization settings (pitch, rate, selected voice).
                // Falls back to sensible defaults if no preferences have been saved yet.
                VoicePreferencesStore.apply(context, tts!!)
                ttsReady = true
                setupListener()
                onReady()
            } else Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun beginStream() {
        stopped.set(false)
        tokenBuffer.clear()
        _state.value = TtsState.SPEAKING
        requestAudioFocus()
        startPlayer()
        AgentActivityBus.emit("Streaming TTS audio", ActivityCategory.VOICE)
    }

    fun onToken(chunk: String) {
        if (stopped.get()) return
        tokenBuffer.append(chunk)
        flushSentences()
    }

    fun endStream() {
        if (stopped.get()) return
        val rem = tokenBuffer.toString().trim()
        if (rem.isNotBlank()) sentenceChannel.trySend(rem)
        tokenBuffer.clear()
    }

    fun stop() {
        stopped.set(true)
        tts?.stop()
        tokenBuffer.clear()
        playerJob?.cancel()
        abandonFocus()
        _state.value = TtsState.STOPPED
    }

    fun release() { stop(); tts?.shutdown(); tts = null; ttsReady = false }

    private fun flushSentences() {
        val text = tokenBuffer.toString()
        val match = Regex("""[.!?\n](?:\s|$)""").find(text) ?: return
        val end = match.range.last + 1
        val sentence = text.substring(0, end).trim()
        if (sentence.length < 3) return
        sentenceChannel.trySend(sentence)
        tokenBuffer.delete(0, end)
    }

    private fun startPlayer() {
        if (playerJob?.isActive == true) return
        playerJob = scope.launch {
            for (sentence in sentenceChannel) {
                if (stopped.get()) break
                if (ttsReady) tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            }
            if (_state.value == TtsState.SPEAKING) { _state.value = TtsState.IDLE; abandonFocus() }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { if (sentenceChannel.isEmpty && !stopped.get()) _state.value = TtsState.IDLE }
            @Deprecated("Deprecated") override fun onError(id: String) { Log.w(TAG, "TTS error: $id") }
        })
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        audioManager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() { focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }; focusRequest = null }
}
