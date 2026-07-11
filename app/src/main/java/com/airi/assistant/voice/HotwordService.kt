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
import ai.picovoice.porcupine.Porcupine
import com.airi.assistant.R
import kotlin.concurrent.thread
// P0-V2: TFLite Interpreter for OpenWakeWord inference
import org.tensorflow.lite.Interpreter

/**
 * Foreground service that runs the Picovoice Porcupine on-device wake-word
 * engine and broadcasts [ACTION_WAKE_WORD] when the user says "Hey AIRI".
 *
 * Strict offline guarantee: this service uses NO network, NO Google APIs,
 * and NO RecognizerIntent. Mic audio never leaves the device.
 *
 * If either the AccessKey or the bundled .ppn keyword file is missing the
 * service writes a clear log line and stops itself immediately. The Voice
 * Settings screen surfaces the same status to the user with instructions.
 */
class HotwordService : Service() {

    @Volatile private var porcupine: Porcupine? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HotwordService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (running) return START_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Hotword: RECORD_AUDIO not granted — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // P0-V2: Try OpenWakeWord first — no API key or account required.
        // Falls back to Porcupine if the .tflite model is not bundled
        // but a Porcupine key + .ppn file are available.
        val owwStatus = OpenWakeWordEngine.status(this)
        if (owwStatus.ready) {
            return startWithOpenWakeWord()
        }

        Log.d(TAG, "OpenWakeWord not ready (${owwStatus.reason}), trying Porcupine")

        val accessKey = PorcupineEngine.accessKey(this)
        val ppnFile   = PorcupineEngine.resolvePpnFile(this)
        if (accessKey.isBlank()) {
            Log.w(TAG, "AIRI_PROOF HOTWORD_DISABLED reason=missing_access_key_and_oww_model — see VoiceSettings")
            stopSelf()
            return START_NOT_STICKY
        }
        if (ppnFile == null) {
            Log.w(TAG, "AIRI_PROOF HOTWORD_DISABLED reason=missing_ppn_and_oww_model — drop hey_airi.ppn or hey_airi.tflite into assets/voice/")
            stopSelf()
            return START_NOT_STICKY
        }

        return startWithPorcupine(accessKey, ppnFile)
    }

    // ── OpenWakeWord engine path ──────────────────────────────────────────

    private fun startWithOpenWakeWord(): Int {
        val modelFile = OpenWakeWordEngine.resolveModelFile(this) ?: run {
            Log.w(TAG, "OWW model resolved to null — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val frameLength = OpenWakeWordEngine.frameSamples
        val sampleRate  = OpenWakeWordEngine.sampleRate

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameLength * 2 * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord (OWW) create failed: ${t.message}", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            Log.w(TAG, "AudioRecord (OWW) not initialized — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Load TFLite interpreter
        val interpreter = try {
            org.tensorflow.lite.Interpreter(modelFile)
        } catch (t: Throwable) {
            Log.w(TAG, "TFLite Interpreter init failed: ${t.message}, falling back to Porcupine", t)
            runCatching { rec.release() }
            // Retry with Porcupine
            val accessKey = PorcupineEngine.accessKey(this)
            val ppnFile   = PorcupineEngine.resolvePpnFile(this)
            if (accessKey.isBlank() || ppnFile == null) { stopSelf(); return START_NOT_STICKY }
            return startWithPorcupine(accessKey, ppnFile)
        }

        audioRecord = rec
        running = true

        captureThread = kotlin.concurrent.thread(name = "AiriOWW", isDaemon = true) {
            try {
                rec.startRecording()
                Log.i(TAG, "AIRI_PROOF HOTWORD_STARTED engine=openWakeWord frameLength=$frameLength sampleRate=$sampleRate")
                val frame   = ShortArray(frameLength)
                // OWW output: single float score in [0,1]
                val output  = Array(1) { FloatArray(1) }

                while (running) {
                    val read = rec.read(frame, 0, frameLength)
                    if (read < frameLength) continue

                    val floatInput = OpenWakeWordEngine.normalizeFrame(frame)
                    val input      = Array(1) { floatInput }
                    try {
                        interpreter.run(input, output)
                        val score = output[0][0]
                        if (score >= OpenWakeWordEngine.threshold) {
                            Log.i(TAG, "AIRI_PROOF OWW_DETECTION score=$score")
                            fireWake(engine = "openWakeWord")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "OWW inference failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "OWW capture loop failed: ${t.message}", t)
            } finally {
                runCatching { rec.stop() }
                runCatching { interpreter.close() }
            }
        }
        return START_STICKY
    }

    // ── Porcupine engine path ─────────────────────────────────────────────

    private fun startWithPorcupine(accessKey: String, ppnFile: java.io.File): Int {
        val pp = try {
            ai.picovoice.porcupine.Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(ppnFile.absolutePath)
                .setSensitivity(0.6f)
                .build(applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Porcupine init failed: ${t.message}", t)
            stopSelf()
            return START_NOT_STICKY
        }
        porcupine = pp

        val frameLength = pp.frameLength
        val sampleRate  = pp.sampleRate

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameLength * 2 * 4)

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
            runCatching { pp.delete() }
            porcupine = null
            stopSelf()
            return START_NOT_STICKY
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            runCatching { pp.delete() }
            porcupine = null
            Log.w(TAG, "AudioRecord not initialized — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        audioRecord = rec
        running = true

        captureThread = kotlin.concurrent.thread(name = "AiriPorcupine", isDaemon = true) {
            try {
                rec.startRecording()
                Log.i(TAG, "AIRI_PROOF HOTWORD_STARTED engine=porcupine frameLength=$frameLength sampleRate=$sampleRate")
                val frame = ShortArray(frameLength)
                while (running) {
                    val read = rec.read(frame, 0, frameLength)
                    if (read <= 0 || read < frameLength) continue
                    val keywordIndex = try { pp.process(frame) } catch (t: Throwable) {
                        Log.w(TAG, "porcupine.process failed: ${t.message}"); -1
                    }
                    if (keywordIndex >= 0) fireWake(engine = "porcupine")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Porcupine capture loop failed: ${t.message}", t)
            } finally {
                runCatching { rec.stop() }
            }
        }
        return START_STICKY
    }

    private fun fireWake(engine: String = "unknown") {
        Log.i(TAG, "AIRI_PROOF HOTWORD_DETECTED engine=$engine")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_FROM_WAKE_WORD, true)
        }
        if (launchIntent != null) {
            try { startActivity(launchIntent) } catch (_: Throwable) {}
        }
        sendBroadcast(Intent(ACTION_WAKE_WORD).setPackage(packageName))
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "AIRI Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.hotword_notification_title))
            .setContentText(getString(R.string.hotword_notification_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        running = false
        try { captureThread?.join(500) } catch (_: Throwable) {}
        captureThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { porcupine?.delete() } catch (_: Throwable) {}
        porcupine = null
        Log.i(TAG, "HotwordService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AIRI_VOICE"
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
