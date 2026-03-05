package com.airi.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var downloader: ModelDownloadManager
    private lateinit var llamaManager: LlamaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloader = ModelDownloadManager(this)
        llamaManager = LlamaManager(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "تفعيل AIRI"
            setOnClickListener {

                if (!downloader.isModelDownloaded()) {
                    checkAndRequestPermissions()
                    return@setOnClickListener
                }

                // ✅ هنا نهيئ النموذج فعلياً قبل أي Overlay
                Toast.makeText(this@MainActivity, "جاري تحميل المحرك...", Toast.LENGTH_SHORT).show()

                llamaManager.initializeModel { success ->

                    if (success) {

                        Toast.makeText(
                            this@MainActivity,
                            "Model loaded successfully",
                            Toast.LENGTH_LONG
                        ).show()

                        // 🔥 اختبار inference مباشر
                        llamaManager.generate("Hello") { reply ->
                            Toast.makeText(
                                this@MainActivity,
                                "AI: $reply",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        // بعد التأكد من نجاح المحرك نتحقق من إذن Overlay
                        checkOverlayPermission()

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "Model load failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        layout.addView(btnStart)
        setContentView(layout)

        requestNotificationPermission()

        if (!downloader.isModelDownloaded()) {
            showDownloadDialog()
        }
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

    private fun showDownloadDialog() {
        AlertDialog.Builder(this)
            .setTitle("تحميل النموذج المطلوب")
            .setMessage("تحتاج AIRI إلى تحميل ملف الذكاء الاصطناعي (حوالي 900MB). هل تود التحميل الآن؟")
            .setPositiveButton("نعم") { _, _ ->
                startLoadingService()
            }
            .setNegativeButton("ليس الآن") { _, _ ->
                Toast.makeText(
                    this,
                    "لن تعمل ميزات الذكاء الاصطناعي بدون النموذج.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun startLoadingService() {
        Toast.makeText(this, "بدأ التحميل.. تابع الإشعارات", Toast.LENGTH_LONG).show()
        val intent = Intent(this, ModelDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
        } else {
            showDownloadDialog()
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
        // تم التغيير هنا إلى DebugOverlayService
        val intent = Intent(this, DebugOverlayService::class.java)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startAiriService()
                } else {
                    Toast.makeText(
                        this,
                        "يرجى منح إذن الظهور فوق التطبيقات",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
