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

        // فلترة الأحداث غير المهمة لتقليل الضغط
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

    /**
     * فلتر منع التكرار
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
     * تنفيذ أوامر الذكاء الاصطناعي
     */
    fun executeCommand(command: String) {

        try {

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

        } catch (e: Exception) {

            Log.e(TAG, "Command execution error", e)
        }
    }

    /**
     * استخراج سياق الشاشة
     */
    fun extractScreenContext(): String {

        val rootNode = rootInActiveWindow
            ?: return ScreenContextHolder.lastScreenText

        val screenText = UITreeScanner.scan(this, rootNode)

        if (screenText.isBlank()) {
            return ScreenContextHolder.lastScreenText
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"

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

    /**
     * استخراج النص من شجرة الواجهة
     */
    private fun extractText(node: AccessibilityNodeInfo?): String {

        if (node == null) return ""

        val builder = StringBuilder()

        node.text?.let {
            builder.append(it).append(" ")
        }

        node.contentDescription?.let {
            builder.append(it).append(" ")
        }

        for (i in 0 until node.childCount) {

            val child = node.getChild(i)

            builder.append(extractText(child))
        }

        return builder.toString()
    }

    /**
     * تخزين السياق في الذاكرة
     */
    private fun storeScreen(text: String) {

        try {

            val prefs = getSharedPreferences(
                "airi_memory",
                MODE_PRIVATE
            )

            prefs.edit()
                .putString("last_screen", text)
                .apply()

        } catch (e: Exception) {

            Log.e(TAG, "Store error", e)
        }
    }

    /**
     * إرسال البيانات للمخ
     */
    private fun sendToBrain(text: String) {

        if (text != ScreenContextHolder.lastScreenText) return

        try {

            BrainManager.processScreen(this, fullContext)

        } catch (e: Exception) {

            Log.e(TAG, "Brain error", e)
        }
    }

    /**
     * تشغيل Overlay
     */
    private fun startOverlay() {

        try {

            val intent = Intent(
                this,
                DebugOverlayService::class.java
            )

            startService(intent)

        } catch (e: Exception) {

            Log.e(TAG, "Overlay start failed", e)
        }
    }

    /**
     * تحديث النص في Overlay
     */
    private fun updateOverlay(text: String) {

        try {

            DebugOverlayService.updateText(
                text.take(150)
            )

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
