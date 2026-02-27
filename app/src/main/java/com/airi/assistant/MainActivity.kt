package com.airi.assistant

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var downloader: ModelDownloadManager
    private lateinit var llamaManager: LlamaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تعريف أدوات الإدارة
        downloader = ModelDownloadManager(this)
        llamaManager = LlamaManager(this)

        // تصميم الواجهة برمجياً (كما كان لديك)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "تفعيل AIRI"
            setOnClickListener {
                // عند الضغط، نتأكد من النموذج أولاً ثم الصلاحيات
                if (downloader.isModelDownloaded()) {
                    checkOverlayPermission()
                } else {
                    checkModel()
                }
            }
        }

        layout.addView(btnStart)
        setContentView(layout)

        // فحص النموذج تلقائياً عند فتح التطبيق
        checkModel()
    }

    private fun checkModel() {
        if (!downloader.isModelDownloaded()) {
            AlertDialog.Builder(this)
                .setTitle("تحميل النموذج المطلوب")
                .setMessage("تحتاج AIRI إلى تحميل ملف الذكاء الاصطناعي (حوالي 900MB). هل تود التحميل الآن؟")
                .setPositiveButton("نعم") { _, _ ->
                    Toast.makeText(this, "بدأ التحميل.. تابع الإشعارات", Toast.LENGTH_LONG).show()
                    downloader.startDownload {
                        // عند اكتمال التحميل، نقوم بتهيئة المحرك
                        try {
                            llamaManager.initializeModel {
                                runOnUiThread {
                                    Toast.makeText(this, "تم تجهيز AIRI بنجاح!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "خطأ في التهيئة: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("ليس الآن") { _, _ ->
                    Toast.makeText(this, "لن تعمل ميزات الذكاء الاصطناعي بدون النموذج.", Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
        } else {
            // إذا كان الموديل موجوداً، نقوم بتهيئته فوراً في الخلفية
            try {
                llamaManager.initializeModel {
                    // جاهز للعمل
                }
            } catch (e: Exception) {
                // ربما هناك مشكلة في الملف أو المكتبة
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
                startActivityForResult(intent, 101)
            } else {
                startAiriService()
            }
        } else {
            startAiriService()
        }
    }

    private fun startAiriService() {
        val intent = Intent(this, OverlayService::class.java)
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
        if (requestCode == 101) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startAiriService()
                } else {
                    Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
