package com.airi.assistant.tools

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.airi.assistant.R

class ModelDownloadService : Service() {

    private val CHANNEL_ID = "model_download"
    private val TAG = "AIRI_MODEL_DOWNLOAD"

    private val defaultUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true"
    private val defaultFileName = "qwen2.5-1.5b-q4_k_m.gguf"
    private val defaultExpectedSize = 934L * 1024L * 1024L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Issue #9 — user-initiated cancel. ChatViewModel / Models UI sends
        // an Intent with action ACTION_CANCEL_DOWNLOAD; we flip the static
        // flag and the running download thread observes it inside its read
        // loop. The partial .part file is then deleted in the catch handler.
        if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            cancelRequested.set(true)
            Log.i(TAG, "CANCEL requested via intent")
            Log.i("AIRI_PROOF", "DOWNLOAD_CANCEL_REQUESTED source=intent")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        cancelRequested.set(false)
        // Authoritative "is a download in progress" flag — set BEFORE the
        // worker thread starts so callers polling cancelActiveDownload()
        // never see a stale FALSE between onStartCommand and the worker's
        // first iteration. Cleared in the worker's finally block.
        isDownloading.set(true)
        val url = intent?.getStringExtra(EXTRA_DOWNLOAD_URL) ?: defaultUrl
        val fileName = intent?.getStringExtra(EXTRA_FILENAME) ?: defaultFileName
        val expectedSize = intent?.getLongExtra(EXTRA_EXPECTED_SIZE_BYTES, defaultExpectedSize) ?: defaultExpectedSize
        startForeground(1, createNotification(fileName))
        startModelDownload(url, fileName, expectedSize)
        return START_STICKY
    }

    private fun createNotification(fileName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Download", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIRI Brain")
            .setContentText("جاري تحميل $fileName ...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    private fun startModelDownload(url: String, fileName: String, expectedSize: Long) {
        Thread {
            val modelsDir = File(getExternalFilesDir(null), "models").apply { if (!exists()) mkdirs() }
            val finalFile = File(modelsDir, fileName)
            val tempFile = File(modelsDir, "$fileName.part")
            var success = false
            var lastError: String? = null
            for (attempt in 1..3) {
                try {
                    if (tempFile.exists()) tempFile.delete()
                    if (cancelRequested.get()) throw InterruptedException("user_cancel_pre_start")
                    Log.i(TAG, "START attempt=$attempt file=$fileName expected=$expectedSize url=$url")
                    Log.i("AIRI_PROOF", "DOWNLOAD_START fileName=$fileName expected=$expectedSize attempt=$attempt")
                    downloadToFile(url, tempFile)
                    val actual = tempFile.length()
                    if (actual < 100_000_000L) throw IllegalStateException("download too small actual=$actual")
                    if (expectedSize > 0 && actual < (expectedSize * 0.97).toLong()) {
                        throw IllegalStateException("size mismatch expected=$expectedSize actual=$actual")
                    }
                    if (finalFile.exists()) finalFile.delete()
                    if (!tempFile.renameTo(finalFile)) throw IllegalStateException("rename failed")
                    Log.i(TAG, "SUCCESS file=${finalFile.absolutePath} size=${finalFile.length()} attempts=$attempt")
                    com.airi.assistant.domain.verification.VerificationTracker.recordCheck("DOWNLOAD", true, "file=${finalFile.absolutePath} size=${finalFile.length()}")
                    Log.i("AIRI_PROOF", "DOWNLOAD_COMPLETE fileName=$fileName path=${finalFile.absolutePath} sizeBytes=${finalFile.length()}")
                    success = true
                    val broadcastIntent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                        putExtra(EXTRA_RESULT_FILENAME, fileName)
                        putExtra(EXTRA_RESULT_PATH, finalFile.absolutePath)
                        `package` = applicationContext.packageName
                    }
                    applicationContext.sendBroadcast(broadcastIntent)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "تم تحميل $fileName بنجاح!", Toast.LENGTH_LONG).show()
                    }
                    break
                } catch (e: InterruptedException) {
                    lastError = "cancelled"
                    Log.i(TAG, "CANCELLED attempt=$attempt file=$fileName reason=${e.message}")
                    Log.i("AIRI_PROOF", "DOWNLOAD_CANCELLED fileName=$fileName partial_bytes=${tempFile.length()}")
                    tempFile.delete()
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "FAILED reason=$lastError attempt=$attempt file=$fileName", e)
                    tempFile.delete()
                    if (cancelRequested.get()) {
                        Log.i("AIRI_PROOF", "DOWNLOAD_CANCELLED fileName=$fileName reason=cancel_during_retry")
                        break
                    }
                    if (attempt < 3) Thread.sleep(1500L * attempt)
                }
            }
            if (!success) {
                val finalReason = if (cancelRequested.get()) "cancelled" else (lastError ?: "unknown")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("DOWNLOAD", false, finalReason)
                if (finalReason != "cancelled") {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "فشل تحميل $fileName: $finalReason", Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Worker is exiting for any reason (success / cancel / failure):
            // clear the authoritative "in progress" flag so the next caller
            // of cancelActiveDownload() gets the truth.
            isDownloading.set(false)
            Log.i("AIRI_PROOF", "DOWNLOAD_WORKER_EXIT success=$success reason=${lastError ?: "ok"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun downloadToFile(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AIRI-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var totalBytes = 0L
                    var lastProofMs = System.currentTimeMillis()
                    while (true) {
                        // Issue #9 — observe cancel inside the read loop so a
                        // tap on the Cancel button stops the transfer within
                        // ~one buffer's worth of bytes (1 MB ≈ <1s on cellular).
                        if (cancelRequested.get()) {
                            throw InterruptedException("user_cancel_during_read bytes=$totalBytes")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalBytes += read
                        // Heart-beat proof every 5s so we can SEE progress in
                        // logcat without polluting the log on every chunk.
                        val now = System.currentTimeMillis()
                        if (now - lastProofMs >= 5000) {
                            android.util.Log.i(
                                "AIRI_PROOF",
                                "DOWNLOAD_PROGRESS bytes=$totalBytes mb=${totalBytes / 1_048_576}"
                            )
                            lastProofMs = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_FILENAME = "download_filename"
        const val EXTRA_EXPECTED_SIZE_BYTES = "download_expected_size_bytes"
        const val ACTION_DOWNLOAD_COMPLETE = "com.airi.assistant.ACTION_DOWNLOAD_COMPLETE"
        const val ACTION_CANCEL_DOWNLOAD = "com.airi.assistant.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_RESULT_FILENAME = "result_filename"
        const val EXTRA_RESULT_PATH = "result_path"

        // Static cancel flag — read inside the worker thread's tight read
        // loop. AtomicBoolean is overkill for a single producer/consumer
        // but it documents the cross-thread intent clearly.
        @JvmStatic
        val cancelRequested: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Authoritative "a download is in progress" flag. Set TRUE in
         * onStartCommand the moment a real download intent arrives, set
         * FALSE in the worker's finally block when the worker exits for
         * ANY reason (success, cancel, exhaustion). Read by
         * ModelDownloadManager.cancelActiveDownload() so the UI can render
         * an accurate "Cancel" / "Download" button without inferring state
         * from the cancel flag.
         */
        @JvmStatic
        val isDownloading: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false)

        /** Convenience for callers that don't want to build an Intent. */
        fun cancel(context: Context) {
            cancelRequested.set(true)
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }
    }
}
