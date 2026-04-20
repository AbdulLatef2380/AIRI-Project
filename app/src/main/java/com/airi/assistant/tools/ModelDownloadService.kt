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

class ModelDownloadService : Service() {

    private val CHANNEL_ID = "model_download"
    private val TAG = "AIRI_MODEL_DOWNLOAD"

    private val defaultUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true"
    private val defaultFileName = "qwen2.5-1.5b-q4_k_m.gguf"
    private val defaultExpectedSize = 934L * 1024L * 1024L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    Log.i(TAG, "START attempt=$attempt file=$fileName expected=$expectedSize url=$url")
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
                    success = true
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "تم تحميل $fileName بنجاح!", Toast.LENGTH_LONG).show()
                    }
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "FAILED reason=$lastError attempt=$attempt file=$fileName", e)
                    tempFile.delete()
                    if (attempt < 3) Thread.sleep(1500L * attempt)
                }
            }
            if (!success) {
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("DOWNLOAD", false, lastError ?: "unknown")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "فشل تحميل $fileName: ${lastError ?: "unknown"}", Toast.LENGTH_LONG).show()
                }
            }
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
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
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
    }
}
