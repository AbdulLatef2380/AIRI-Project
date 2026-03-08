package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.widget.Toast
import android.os.Build
import com.airi.assistant.overlay.DebugOverlayService
import com.airi.assistant.brain.BrainManager
import com.airi.assistant.learning.UILearningEngine

class AIRIAccessibilityService : AccessibilityService() {

    private var lastEventTime = 0L
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
        val now = System.currentTimeMillis()

        if (now - lastEventTime < 800) {
            return
        }
        lastEventTime = now

        val root = rootInActiveWindow ?: return

        val fullContext = UITreeScanner.scanTree(root)

        if (fullContext.isNotEmpty() &&
            fullContext != ScreenContextHolder.lastProcessedContext) {

            ScreenContextHolder.lastProcessedContext = fullContext

            BrainManager.processScreenContext(fullContext, this)
        }

        processScreenContext(root)
    }

    private fun shouldUpdateContext(newText: String): Boolean {
        val now = System.currentTimeMillis()
        if (newText == lastScreenTextInstance) return false
        if (now - lastUpdateTime < UPDATE_DELAY) return false

        lastScreenTextInstance = newText
        lastUpdateTime = now
        return true
    }

    fun executeCommand(command: String) {
        try {
            when {
                command.contains("اضغط", true) -> {
                    val target = command
                        .replace("اضغط", "", true)
                        .replace("على", "", true)
                        .trim()
                    SmartActionEngine.smartClick(this, target)
                }
                command.contains("اكتب", true) -> {
                    val text = command.replace("اكتب", "", true).trim()
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

    fun extractScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val screenText = UITreeScanner.scan(this, rootNode)

        if (screenText.isBlank()) {
            return ScreenContextHolder.lastScreenText
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"

        UILearningEngine.learnScreen(this, packageName, rootNode)

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
        try {
            BrainManager.processScreen(this, text)
        } catch (e: Exception) {
            Log.e(TAG, "Brain error", e)
        }
    }

    private fun startOverlay() {
        try {
            val intent = Intent(this, DebugOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
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

    private fun processScreenContext(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrEmpty()) {
            Log.d("AIRI_UI", "Text: $text")
        }

        if (!desc.isNullOrEmpty()) {
            Log.d("AIRI_UI", "Desc: $desc")
        }

        for (i in 0 until node.childCount) {
            processScreenContext(node.getChild(i))
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
