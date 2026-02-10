package com.airi.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.airi.core.NativeBridge

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // المرحلة C: ربط نظيف (Clean Bridge)
        // تهيئة المحرك عند الإقلاع بشكل صامت
        try {
            NativeBridge.initEngine()
        } catch (e: Exception) {
            Log.e("AIRI_NATIVE", "فشل تهيئة المحرك: ${e.message}")
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "تفعيل AIRI"
            setOnClickListener {
                checkOverlayPermission()
            }
        }

        val btnN8nTest = Button(this).apply {
            text = "اختبار تكامل n8n"
            setOnClickListener {
                testN8nIntegration()
            }
        }

        layout.addView(btnStart)
        layout.addView(btnN8nTest)
        setContentView(layout)
    }

    private fun testN8nIntegration() {
        val policyEngine = PolicyEngine()
        val n8n = N8nIntegration()
        
        val intent = "automation_request"
        val action = "create_task"
        
        // 1. Policy Evaluation (The Authority Layer)
        val evaluation = policyEngine.evaluate(intent, action)
        
        // 2. Immutable Audit Logging
        AuditManager.logDecision(evaluation, intent, action)
        
        if (!evaluation.isAllowed) {
            Toast.makeText(this, "تم حظر العملية: ${evaluation.reason}", Toast.LENGTH_LONG).show()
            return
        }

        // 3. Execution (Only if allowed)
        lifecycleScope.launch {
            val result = n8n.sendAutomationRequest(
                intent = intent,
                action = action,
                title = "اختبار من AIRI (تحت الرقابة)",
                priority = "high",
                context = evaluation.constraints["scope"] ?: "general"
            )
            if (result != null) {
                Toast.makeText(this@MainActivity, "تم إرسال الطلب لـ n8n بنجاح", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "فشل الاتصال بـ n8n", Toast.LENGTH_SHORT).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "تم تفعيل AIRI بنجاح", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startAiriService()
                } else {
                    Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات لتفعيل AIRI", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
