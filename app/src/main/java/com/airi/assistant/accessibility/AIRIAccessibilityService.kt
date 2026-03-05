package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import android.widget.Toast
import com.airi.assistant.overlay.OverlayService
import com.airi.assistant.brain.BrainManager
class AIRIAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AIRI_ACCESS"
        var lastScreenText: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.i(TAG, "AIRI Accessibility Connected")

        Toast.makeText(
            applicationContext,
            "AIRI Screen Reader Active",
            Toast.LENGTH_SHORT
        ).show()

        startOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val rootNode = rootInActiveWindow ?: return

        val screenText = extractText(rootNode)

        if (screenText.isBlank()) return

        if (screenText == lastScreenText) return

        lastScreenText = screenText

        Log.d(TAG, "Screen captured")

        storeScreen(screenText)

        sendToBrain(screenText)

        updateOverlay(screenText)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Interrupted")
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

            val intent = Intent(this, OverlayService::class.java)

            startService(intent)

        } catch (e: Exception) {

            Log.e(TAG, "Overlay start failed")
        }
    }

    private fun updateOverlay(text: String) {

        try {

            OverlayService.updateText(text.take(120))

        } catch (e: Exception) {

            Log.e(TAG, "Overlay update error")
        }
    }
}
