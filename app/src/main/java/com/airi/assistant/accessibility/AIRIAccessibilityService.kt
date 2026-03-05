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

    companion object {
        private const val TAG = "AIRI_ACCESS"
        var lastScreenText: String = ""
        var lastPackage: String = ""
    } 
    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.i(TAG, "AIRI Accessibility Connected")

        ScreenContextHolder.serviceInstance = this

        Toast.makeText(
            applicationContext,
            "AIRI Screen Reader Active",
            Toast.LENGTH_SHORT
        ).show()

        startOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        
        // استدعاء الدالة المحدثة التي تحتوي على Guard
        val fullContext = extractScreenContext()

        // إذا كانت الدالة قد أعادت نفس السياق القديم (بسبب الـ Hash)، لا تكمل المعالجة
        // ملاحظة: الـ Hash يتم فحصه وتحديثه داخل extractScreenContext
        
        Log.d(TAG, "Event processed from $packageName")

        storeScreen(fullContext)
        sendToBrain(fullContext)
        updateOverlay(fullContext)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenContextHolder.reset()
    }

    /**
     * 🔥 الدالة المحدثة مع نظام حماية استقرار السياق (Stability Guard)
     */
    fun extractScreenContext(): String {

        val rootNode = rootInActiveWindow ?: return ScreenContextHolder.lastScreenText

        val screenText = extractText(rootNode).trim()

        if (screenText.isBlank()) {
            return ScreenContextHolder.lastScreenText
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val fullContext = "App:$packageName | $screenText"

        // 🧠 Context Stability Guard
        val newHash = fullContext.hashCode()

        if (newHash == ScreenContextHolder.lastContextHash) {
            // نفس الشاشة ونفس التطبيق → تجاهل التحديث
            return ScreenContextHolder.lastScreenText
        }

        // تحديث البصمة والبيانات في الـ Holder
        ScreenContextHolder.lastContextHash = newHash
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
            prefs.edit()
                .putString("last_screen", text)
                .apply()
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
}
