package com.airi.assistant.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import kotlin.concurrent.thread

/**
 * HotwordService — fully on-device wake-word detection using [InternalWakeWordEngine].
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────
 *
 *   Replaces the former Picovoice Porcupine implementation with Vosk-based
 *   keyword spotting. No proprietary SDK, no API key, no network dependency.
 *
 *   The wake phrase is "Hey AIRI".  Detection is grammar-constrained so Vosk
 *   only attempts to recognise that specific phrase, reducing CPU and
 *   false-positive rate significantly.
 *
 * ── REQUIREMENTS ─────────────────────────────────────────────────────────
 *
 *   1. RECORD_AUDIO permission must be granted.
 *   2. At least one Vosk model must be installed via [VoskModelManager].
 *      The active model is used. If none is installed the service stops
 *      gracefully and logs a clear AIRI_PROOF tag.
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────
 *
 *   1. [onStartCommand] launches a coroutine to load the Vosk model.
 *   2. Once loaded, a background capture thread feeds AudioRecord frames
 *      to [InternalWakeWordEngine].
 *   3. On detection: the main activity is brought to foreground and
 *      [ACTION_WAKE_WORD] is broadcast to any listeners.
 *   4. [onDestroy] stops the capture thread and releases resources.
 *
 * ── PROOF TAGS ───────────────────────────────────────────────────────────
 *
 *   HOTWORD_STARTED  — service ready, capture loop running
 *   HOTWORD_DETECTED — wake phrase confirmed
 *   HOTWORD_DISABLED — stopped due to missing model or permission
 */
class HotwordService : Service() {

    @Volatile private var model:        Model?                  = null
    @Volatile private var wakeEngine:   InternalWakeWordEngine? = null
    @Volatile private var audioRecord:  AudioRecord?            = null
    @Volatile private var captureThread: Thread?                = null
    @Volatile private var running = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HotwordService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (running) return START_STICKY

        // Permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "AIRI_PROOF HOTWORD_DISABLED reason=missing_record_audio_permission")
            stopSelf()
            return START_NOT_STICKY
        }

        // Model loading is async — don't block onStartCommand
        scope.launch {
            val loadedModel = withContext(Dispatchers.IO) {
                InternalWakeWordEngine.loadModel(applicationContext)
            }

            if (loadedModel == null) {
                Log.w(TAG, "AIRI_PROOF HOTWORD_DISABLED reason=no_vosk_model_installed — " +
                    "install a Vosk model in Voice Settings to enable wake-word detection")
                stopSelf()
                return@launch
            }

            model = loadedModel

            val engine = InternalWakeWordEngine(
                model = loadedModel,
                onWakeDetected = {
                    fireWake()
                }
            )
            wakeEngine = engine

            startCapture(engine)
        }

        return START_STICKY
    }

    // ── Audio Capture ─────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun startCapture(engine: InternalWakeWordEngine) {
        val sampleRate  = 16_000
        val frameSize   = 4_000  // 250ms of frames at 16kHz
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameSize * 2 * 4) // shorts→bytes ×2, generous buffer

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord create failed: ${t.message}", t)
            cleanupModel()
            stopSelf()
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            Log.w(TAG, "AudioRecord not initialized — stopping HotwordService")
            cleanupModel()
            stopSelf()
            return
        }

        audioRecord = rec
        running     = true

        captureThread = thread(name = "AiriInternalKWS", isDaemon = true) {
            try {
                rec.startRecording()
                Log.i(TAG, "AIRI_PROOF HOTWORD_STARTED engine=vosk_internal sampleRate=$sampleRate frameSize=$frameSize")
                val frame = ShortArray(frameSize)
                while (running) {
                    val nRead = rec.read(frame, 0, frameSize)
                    if (nRead <= 0) continue
                    engine.processFrame(frame, nRead)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "KWS capture loop error: ${t.message}", t)
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
            }
        }
    }

    // ── Wake Detection ────────────────────────────────────────────────────────

    private fun fireWake() {
        Log.i(TAG, "AIRI_PROOF HOTWORD_DETECTED engine=vosk_internal")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_FROM_WAKE_WORD, true)
        }
        if (launchIntent != null) {
            try { startActivity(launchIntent) } catch (_: Throwable) {}
        }
        sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName))
    }

    // ── Foreground Notification ───────────────────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "AIRI Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        val pi = packageManager.getLaunchIntentForPackage(packageName)?.let { i ->
            PendingIntent.getActivity(
                this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AIRI is listening for \"Hey AIRI\"")
            .setContentText("On-device wake word • no cloud dependency")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        running = false
        scope.cancel()
        try { captureThread?.join(500) } catch (_: Throwable) {}
        captureThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        cleanupModel()
        Log.i(TAG, "HotwordService destroyed")
        super.onDestroy()
    }

    private fun cleanupModel() {
        try { wakeEngine?.close() } catch (_: Throwable) {}
        wakeEngine = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG        = "AIRI_VOICE"
        private const val CHANNEL_ID = "airi_hotword"
        private const val NOTIF_ID   = 4711

        const val ACTION_WAKE_WORD     = "com.airi.assistant.HOTWORD_DETECTED"
        const val EXTRA_FROM_WAKE_WORD = "com.airi.assistant.FROM_WAKE_WORD"

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
