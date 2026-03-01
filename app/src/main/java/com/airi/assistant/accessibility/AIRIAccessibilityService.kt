package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.*

class AIRIAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val root = rootInActiveWindow ?: return
            val screenText = extractText(root)

            if (screenText.isBlank()) return

            val sourceApp = event.packageName?.toString() ?: "unknown"

            // 🔥 1️⃣ حفظ السياق
            ContextEngine.saveContext(
                screenText = screenText,
                sourceApp = sourceApp,
                detectedIntent = "AUTO_DETECT"
            )

            // 🔥 2️⃣ توليد اقتراحات ذكية
            val suggestions = SuggestionEngine.generateSuggestions(screenText)

            if (suggestions.isNotEmpty()) {
                OverlayBridge.showSuggestion(
                    suggestions.first(),
                    screenText
                )
            }

            Log.d("AIRI_CONTEXT", "Context captured from $sourceApp")
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val builder = StringBuilder()

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return

            n.text?.let { builder.append(it).append(" ") }
            n.contentDescription?.let { builder.append(it).append(" ") }

            for (i in 0 until n.childCount) {
                traverse(n.getChild(i))
            }
        }

        traverse(node)

        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000) // منع التضخم
    }

    override fun onInterrupt() {}
}
