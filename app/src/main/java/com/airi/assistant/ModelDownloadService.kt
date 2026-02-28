package com.airi.assistant

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File

class ModelDownloadService : Service() {
    private val CHANNEL_ID = "model_download"
    // إضافة ?download=true لضمان التحميل المباشر من HuggingFace
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true"
    private val modelName = "qwen2.5-1.5b-q4_k_m.gguf"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        startModelDownload()
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIRI Brain")
            .setContentText("جاري تحميل محرك الذكاء الاصطناعي...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            Log.e("AIRI_DEBUG", "Error starting foreground: ${e.message}")
        }
    }

    private fun startModelDownload() {
        // تم حذف أسطر File(dir) و modelFile لأن DownloadManager سيتولى ذلك
        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("AIRI Model")
            .setDescription("جاري تحميل ملف الذكاء الاصطناعي...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            // استخدام المسار الخارجي المخصص للتطبيق لضمان صلاحيات الكتابة
            .setDestinationInExternalFilesDir(
                this,
                null,
                "models/$modelName"
            )

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    unregisterReceiver(this)
                    Toast.makeText(applicationContext, "تم تحميل النموذج بنجاح!", Toast.LENGTH_LONG).show()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        // تسجيل الـ Receiver مع مراعاة أمان أندرويد 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "Download Service", 
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
