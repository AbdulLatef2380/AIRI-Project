package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.widget.Toast
import com.airi.assistant.overlay.DebugOverlayService
import com.airi.assistant.brain.BrainManager
import com.airi.assistant.learning.UILearningEngine

class AIRIAccessibilityService : AccessibilityService() {

    private var lastScreenTextInstance: String = ""
    private var lastUpdateTime: Long = 0
    private val UPDATE_DELAY = 1200L

    companion object {
        private const val TAG = "AIRI_ACCESS"
        var instance: AIRIAccessibilityService? = null
        var lastScreenText: String = ""
        var lastPackage: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                
                val fullContext = extractScreenContext()

                if (fullContext.isNotEmpty()) {
                    storeScreen(fullContext)
                    sendToBrain(fullContext)
                    updateOverlay(fullContext)
                }
            }
            else -> return
        }
    }

    private fun shouldUpdateContext(newText: String): Boolean {
        val now = System.currentTimeMillis()
        if (newText == lastScreenTextInstance) return false
        if (now - lastUpdateTime < UPDATE_DELAY) return false

        lastScreenTextInstance = newText
        lastUpdateTime = now
        return true
    }

    /**
     * تنفيذ الأوامر الصوتية أو البرمجية الموجهة للمساعد
     */
    fun executeCommand(command: String) {
        try {
            when {
                command.contains("اضغط", true) -> {
                    val target = command.replace("اضغط", "").trim()
                    SmartActionEngine.smartClick(this, target)
                }
                command.contains("اكتب", true) -> {
                    val text = command.replace("اكتب", "").trim()
                    ActionExecutor.inputText(this, text)
                }
                command.contains("رجوع", true) -> {
                    ActionExecutor.pressBack(this)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error", e)
        }
    }

    /**
     * استخراج سياق الشاشة وتعلم الأنماط الجديدة تلقائياً
     */
    fun extractScreenContext(): String {
        val rootNode = rootInActiveWindow
            ?: return ScreenContextHolder.lastScreenText

        // مسح الشجرة وتحويلها لنص
        val screenText = UITreeScanner.scan(this, rootNode)

        if (screenText.isBlank()) {
            return ScreenContextHolder.lastScreenText
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"

        // ✅ إدراج محرك التعلم (UILearningEngine) هنا
        // لربط اسم الحزمة بهيكل الشاشة الحالي في الذاكرة
        UILearningEngine.learnScreen(
            this,
            packageName,
            rootNode
        )

        val fullContext = "App:$packageName | $screenText"

        if (shouldUpdateContext(fullContext)) {
            ScreenContextHolder.lastScreenText = fullContext
            ScreenContextHolder.lastContextHash = fullContext.hashCode()

            lastScreenText = fullContext
            lastPackage = packageName

            Log.d(TAG, "AIRI sees: $fullContext")
            return fullContext
        }

        return ScreenContextHolder.lastScreenText
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
        // نمرر السياق للعقل لاتخاذ القرار
        try {
            BrainManager.processScreen(this, text)
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

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        ScreenContextHolder.reset()
    }
}
