package com.airi.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * NativeSttEngine — wraps Android's built-in SpeechRecognizer.
 *
 * Works out-of-the-box on every Android device with a system voice service
 * (Google, Samsung, AOSP, etc.).  No model download, no API key, no external
 * SDK.  Arabic-first with English fallback — matches AIRI's RTL-first design.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 * ─────────────────────────────────────────────────────────────────────────
 * SpeechRecognizer MUST be created, used, and destroyed on the MAIN thread.
 * All public methods enforce this by delegating to the main Looper via
 * android.os.Handler when the caller is on a background thread.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LIFECYCLE
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Create a fresh NativeSttEngine per listen turn.
 * 2. Call start() to begin recognition.
 * 3. One of {onFinal, onError} is guaranteed to fire exactly once.
 * 4. Call stop() / release() if the caller wants to cancel early.
 */
class NativeSttEngine(private val context: Context) {

    companion object {
        private const val TAG = "AIRI_NATIVE_STT"

        /** Returns true if the system STT service is available on this device. */
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)

        private fun errorLabel(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO                  -> "audio_hardware_error"
            SpeechRecognizer.ERROR_CLIENT                 -> "client_side_error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing_mic_permission"
            SpeechRecognizer.ERROR_NETWORK                -> "network_unavailable"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH               -> "no_speech_detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "recognizer_busy"
            SpeechRecognizer.ERROR_SERVER                 -> "server_error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "speech_timeout"
            7                                             -> "too_many_requests"
            else                                          -> "stt_error_$error"
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile private var stopped   = false
    @Volatile private var callbackFired = false

    /**
     * Begin a single listen turn.
     *
     * @param locale  BCP-47 locale string, e.g. "ar-SA" or "en-US".
     *                Defaults to Arabic (AIRI's primary language).
     * @param onPartial  called with interim results (may be called many times).
     * @param onFinal    called once with the final transcription (may be empty).
     * @param onError    called once if recognition fails.
     */
    fun start(
        locale:    String             = "ar-SA",
        onPartial: (String) -> Unit   = {},
        onFinal:   (String) -> Unit,
        onError:   (String) -> Unit
    ) {
        mainHandler.post {
            if (stopped) return@post

            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            recognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech locale=$locale")
                }
                override fun onBeginningOfSpeech()  { Log.d(TAG, "onBeginningOfSpeech") }
                override fun onRmsChanged(db: Float) {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech()        { Log.d(TAG, "onEndOfSpeech") }

                override fun onError(error: Int) {
                    if (stopped || !fireOnce()) return
                    val msg = errorLabel(error)
                    Log.w(TAG, "STT error $error → $msg")
                    destroyRecognizer()
                    onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    if (stopped || !fireOnce()) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty().trim()
                    Log.d(TAG, "onResults: '${text.take(80)}'")
                    destroyRecognizer()
                    onFinal(text)
                }

                override fun onPartialResults(partial: Bundle?) {
                    if (stopped) return
                    val matches = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty().trim()
                    if (text.isNotBlank()) onPartial(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_SPECIFIC_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_ARRAY, arrayOf(locale, "en-US", "ar-SA"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            sr.startListening(intent)
            Log.i(TAG, "Native STT started locale=$locale")
        }
    }

    /**
     * Stop recognition early and cancel any pending callbacks.
     * Safe to call multiple times (idempotent).
     */
    fun stop() {
        stopped = true
        mainHandler.post { destroyRecognizer() }
    }

    /** Alias matching VoskEngine API for drop-in substitution. */
    fun release() = stop()

    // ── internal ──────────────────────────────────────────────────────────

    private fun fireOnce(): Boolean {
        if (callbackFired) return false
        callbackFired = true
        return true
    }

    private fun destroyRecognizer() {
        try { recognizer?.stopListening()  } catch (_: Throwable) {}
        try { recognizer?.cancel()         } catch (_: Throwable) {}
        try { recognizer?.destroy()        } catch (_: Throwable) {}
        recognizer = null
    }
}
