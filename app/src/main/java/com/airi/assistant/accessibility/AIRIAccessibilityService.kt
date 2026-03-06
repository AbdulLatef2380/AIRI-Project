package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.widget.Toast
import com.airi.assistant.overlay.DebugOverlayService
import com.airi.assistant.brain.BrainManager

class AIRIAccessibilityService : AccessibilityService() {

    // المتغيرات التي أضفناها في الخطوة السابقة
    private var lastScreenTextInstance: String = "" // قمت بتغيير الاسم قليلاً لتجنب التضارب مع الـ Companion
    private var lastUpdateTime: Long = 0
    private val UPDATE_DELAY = 1200L

    companion object {
        private const val TAG = "AIRI_ACCESS"
        var lastScreenText: String = ""
        var lastPackage: String = ""
    } 

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AIRI Accessibility Connected")
        ScreenContextHolder.serviceInstance = this
        Toast.makeText(applicationContext, "AIRI Screen Reader Active", Toast.LENGTH_SHORT).show()
        startOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        
        // استخراج السياق (الدالة بالأسفل تستخدم الآن فلتر الوقت)
        val fullContext = extractScreenContext()

        // إذا كانت الدالة فارغة أو لم تتجاوز الفلتر الزمني، سنتوقف هنا
        if (fullContext.isEmpty() || fullContext == ScreenContextHolder.lastScreenText && lastUpdateTime != System.currentTimeMillis()) {
            // ملاحظة: إذا أعادت الدالة نفس النص القديم فهذا يعني أن الفلتر منع التحديث
        } else {
            Log.d(TAG, "New stable context from $packageName")
            storeScreen(fullContext)
            sendToBrain(fullContext)
            updateOverlay(fullContext)
        }
    }

    /**
     * 🛡️ فلتر التكرار والاستقرار الزمني
     */
    private fun shouldUpdateContext(newText: String): Boolean {
        val now = System.currentTimeMillis()

        // 1. فحص التكرار النصي
        if (newText == lastScreenTextInstance) {
            return false
        }

        // 2. فحص التأخير الزمني (Debounce)
        if (now - lastUpdateTime < UPDATE_DELAY) {
            return false
        }

        // تحديث الحالة إذا اجتاز الشروط
        lastScreenTextInstance = newText
        lastUpdateTime = now
        return true
    }

    fun extractScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return ScreenContextHolder.lastScreenText
        val screenText = extractText(rootNode).trim()

        if (screenText.isBlank()) return ScreenContextHolder.lastScreenText

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val fullContext = "App:$packageName | $screenText"

        // 🔥 تطبيق الفلتر الجديد هنا قبل إرسال النص للمخ أو الـ Holder
        if (!shouldUpdateContext(fullContext)) {
            return ScreenContextHolder.lastScreenText
        }

        // إذا وصلنا هنا، يعني أن النص جديد والوقت كافٍ
        ScreenContextHolder.lastContextHash = fullContext.hashCode()
        ScreenContextHolder.lastScreenText = fullContext
        
        lastScreenText = fullContext
        lastPackage = packageName

        return fullContext
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()
        if (!node.text.isNullOrEmpty()) {
            builder.append(node.text).append(" ")
        }
        if (!node.contentDescription.isNullOrEmpty()) {
            builder.append(node.contentDescription).append(" ")
        }
        for (i in 0 until node.childCount) {
            builder.append(extractText(node.getChild(i)))
        }
        return builder.toString()
    }

    private fun storeScreen(text: String) {
        try {
            val prefs = getSharedPreferences("airi_memory", MODE_PRIVATE)
            prefs.edit().putString("last_screen", text).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Store error", e)
        }
    }

    private fun sendToBrain(text: String) {
        try {
            BrainManager.processScreen(text)
        } catch (e: Exception) {
            Log.e(TAG, "Brain error", e)
        }
    }

    private fun startOverlay() {
        try {
            val intent = Intent(this, DebugOverlayService::class.java)
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay start failed", e)
        }
    }

    private fun updateOverlay(text: String) {
        try {
            DebugOverlayService.updateText(text.take(150))
        } catch (e: Exception) {
            Log.e(TAG, "Overlay update error", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        ScreenContextHolder.reset()
    }
}
