package com.airi.assistant.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airi.assistant.ai.*
import com.airi.assistant.tools.FileUtils
import com.airi.assistant.tools.ModelDownloadManager
import com.airi.assistant.ui.theme.AIRITheme
import com.airi.assistant.ui.chat.AiriApp

class MainActivity : ComponentActivity() {

    private lateinit var downloader: ModelDownloadManager
    private lateinit var llamaManager: LlamaManager

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val path = FileUtils.copyToInternalStorage(this, it)
                val model = ModelInfo(
                    name = "Local Model ${System.currentTimeMillis()}",
                    fileName = "model.gguf",
                    size = 0,
                    quantization = "unknown",
                    path = path,
                    source = ModelSource.LOCAL_FILE
                )
                ModelRegistry.register(model)
                Toast.makeText(this, "تم استيراد النموذج بنجاح", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloader = ModelDownloadManager(this)
        llamaManager = LlamaManager(this)
        
        // تهيئة نظام النماذج
        ModelManager.setLoader(ModelLoader(llamaManager))

        setContent {
            AIRITheme {
                AiriApp(
                    onImportModel = { pickModelLauncher.launch(arrayOf("*/*")) },
                    onStartAiri = { checkOverlayPermission() }
                )
            }
        }

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 102)
            } else {
                startAiriService()
            }
        } else {
            startAiriService()
        }
    }

    private fun startAiriService() {
        val intent = Intent(this, com.airi.assistant.ui.OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "تم تفعيل AIRI بنجاح", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
