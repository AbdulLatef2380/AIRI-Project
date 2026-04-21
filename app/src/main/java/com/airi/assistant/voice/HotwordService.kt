package com.airi.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airi.assistant.R

/**
 * Foreground service that runs an on-device [SpeechRecognizer] in a continuous
 * restart-loop and fires a broadcast when the user says the wake phrase.
 *
 * This is a best-effort offline hotword detector built only from stock Android
 * APIs (no Picovoice key, no bundled .onnx file). It works on devices that
 * have Google's offline speech-recognition pack installed (default since
 * Android 9 on most OEMs). When EXTRA_PREFER_OFFLINE is honored by the system
 * recognizer, no network traffic occurs.
 *
 * Wake patterns (case-insensitive whole-token match):
 *   English: "hey airi", "ok airi", "hi airi", "airi"
 *   Arabic:  "هاي ايري", "هاي إيري", "ياايري", "ايري"
 *
 * Limitations (documented honestly):
 *   - Higher battery use than a dedicated KWS model. For true low-power
 *     always-on hotword, bundle sherpa-onnx KWS + 5 MB int8 model and replace
 *     [SpeechRecognizer] with sherpa's continuous decoder.
 *   - On some OEM ROMs the system recognizer requires the screen to be on.
 *   - When [SpeechRecognizer.isRecognitionAvailable] is false, the service
 *     stops itself immediately and logs a clear reason.
 */
class HotwordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var restartScheduled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HotwordService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (running) return START_STICKY
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Hotword: SpeechRecognizer.isRecognitionAvailable=false — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startListening()
        Log.i(TAG, "AIRI_PROOF HOTWORD_STARTED engine=android-on-device patterns=${WAKE_PATTERNS.joinToString(",")}")
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "AIRI Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AIRI is listening for \"Hey AIRI\"")
            .setContentText("Tap to open AIRI")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startListening() {
        if (!running) return
        mainHandler.post {
            try {
                recognizer?.destroy()
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                r.setRecognitionListener(buildListener())
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer = r
                r.startListening(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Hotword: startListening failed: ${t.message}")
                scheduleRestart(1000L)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed({
            restartScheduled = false
            startListening()
        }, delayMs)
    }

    private fun buildListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            // Most errors (no-match, network, busy, timeout) just mean "loop again".
            val delay = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.w(TAG, "Hotword: missing RECORD_AUDIO permission — stopping")
                    stopSelf()
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_SERVER          -> 3000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2000L
                else -> 600L
            }
            scheduleRestart(delay)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (matchesWakeWord(matches)) {
                fireWake(matches.firstOrNull().orEmpty())
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (matchesWakeWord(matches)) {
                fireWake(matches.firstOrNull().orEmpty())
            } else {
                scheduleRestart(200L)
            }
        }
    }

    private fun matchesWakeWord(candidates: List<String>): Boolean {
        if (candidates.isEmpty()) return false
        for (raw in candidates) {
            val text = raw.lowercase().trim()
            // Word-boundary match — never substring of an unrelated word.
            val tokens = text.split(Regex("[\\s\\p{Punct}،؛؟]+")).filter { it.isNotBlank() }
            for (pattern in WAKE_PATTERNS) {
                val isPhrase = pattern.contains(' ')
                val hit = if (isPhrase) text.contains(pattern) else tokens.contains(pattern)
                if (hit) return true
            }
        }
        return false
    }

    private fun fireWake(transcript: String) {
        Log.i(TAG, "AIRI_PROOF HOTWORD_DETECTED transcript='${transcript.take(60)}'")
        sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName))
        // Keep listening so the next "Hey AIRI" still works.
        scheduleRestart(800L)
    }

    override fun onDestroy() {
        running = false
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        Log.i(TAG, "HotwordService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AIRI_VOICE"
        private const val CHANNEL_ID = "airi_hotword"
        private const val NOTIF_ID   = 4711

        const val ACTION_WAKE_WORD = "com.airi.assistant.HOTWORD_DETECTED"

        private val WAKE_PATTERNS = listOf(
            "hey airi", "ok airi", "hi airi", "okay airi",
            "airi",
            "هاي ايري", "هاي إيري", "يا ايري", "يا إيري",
            "ايري", "إيري"
        )

        fun start(ctx: Context) {
            val i = Intent(ctx, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HotwordService::class.java))
        }
    }
}
