package com.airi.assistant

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File

class ModelDownloadManager(private val context: Context) {

    // الرابط المباشر لنموذج Qwen 2.5 1.5B (حوالي 950MB)
    private val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    private val modelName = "qwen2.5-1.5b-q4_k_m.gguf"

    fun getModelFile(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs() 
        return File(dir, modelName)
    }

    fun isModelDownloaded(): Boolean {
        return getModelFile().exists()
    }

    fun startDownload(onComplete: () -> Unit) {
        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("Downloading AIRI Brain")
            .setDescription("Preparing AI model...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true) 
            .setDestinationUri(Uri.fromFile(getModelFile()))

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    Toast.makeText(context, "AIRI Brain Ready", Toast.LENGTH_LONG).show()
                    onComplete()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
}
