package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.widget.Toast
import com.airi.assistant.overlay.DebugOverlayService
import com.airi.assistant.brain.BrainManager
import com.airi.assistant.accessibility.ActionExecutor

class AIRIAccessibilityService : AccessibilityService() {

    private var lastScreenTextInstance: String = "" 
    private var lastUpdateTime: Long = 0
    private val UPDATE_DELAY = 1200L

    companion object {
        private const val TAG = "AIRI_ACCESS"

        // ✅ إضافة الـ instance للوصول للخدمة من أي مكان
        var instance: AIRIAccessibilityService? = null

        var lastScreenText: String = ""
        var lastPackage: String = ""
    } 

    override fun onServiceConnected() {
        super.onServiceConnected()

        // ✅ تعيين الـ instance عند اتصال الخدمة
        instance = this

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
        
        val fullContext = extractScreenContext()

        if (fullContext.isNotEmpty()) {
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

        if (newText == lastScreenTextInstance) {
            return false
        }

        if (now - lastUpdateTime < UPDATE_DELAY) {
            return false
        }

        lastScreenTextInstance = newText
        lastUpdateTime = now
        return true
    }

    /**
     * ⚡ تنفيذ الأوامر القادمة من الذكاء الاصطناعي
     */
    fun executeCommand(command: String) {
        when {
            command.contains("اضغط", true) -> {
                val target = command.replace("اضغط", "").trim()
                ActionExecutor.clickByText(this, target)
            }
            command.contains("اكتب", true) -> {
                val text = command.replace("اكتب", "").trim()
                ActionExecutor.inputText(this, text)
            }
            command.contains("رجوع", true) -> {
                ActionExecutor.pressBack(this)
            }
        }
    }

    fun extractScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return ScreenContextHolder.lastScreenText
        val screenText = extractText(rootNode).trim()

        if (screenText.isBlank()) return ScreenContextHolder.lastScreenText

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val fullContext = "App:$packageName | $screenText"

        if (shouldUpdateContext(fullContext)) {
            
            ScreenContextHolder.lastScreenText = fullContext
            ScreenContextHolder.lastContextHash = fullContext.hashCode()
            
            lastScreenText = fullContext
            lastPackage = packageName

            Log.d("AIRI", "AIRI sees: $fullContext")
            
            return fullContext
        }

        return ScreenContextHolder.lastScreenText
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
        if (text == ScreenContextHolder.lastScreenText) {
            try {
                BrainManager.processScreen(text)
            } catch (e: Exception) {
                Log.e(TAG, "Brain error", e)
            }
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
        // ✅ تنظيف الـ instance عند تدمير الخدمة
        instance = null
        super.onDestroy()
        ScreenContextHolder.reset()
    }
}
