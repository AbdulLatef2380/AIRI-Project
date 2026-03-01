package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.accessibility.OverlayBridge

class AIRIAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedHash = 0
    private var debounceRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
    }

    /**
     * ✅ المحرك الاستباقي مع Debounce و Hash Guard
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // نراقب تغير النافذة أو المحتوى
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Debounce: انتظر 500ms قبل المعالجة (لتجنب التكرار أثناء السكرول)
            debounceRunnable?.let { handler.removeCallbacks(it) }
            debounceRunnable = Runnable {
                processCurrentScreen()
            }
            handler.postDelayed(debounceRunnable!!, 500)
        }
    }

    private fun processCurrentScreen() {
        val context = extractScreenContext()
        val currentHash = context.hashCode()

        // 🛡️ Hash Guard: إذا لم يتغير المحتوى الفعلي، لا تفعل شيئاً
        if (currentHash == lastProcessedHash) return
        lastProcessedHash = currentHash

        // 🔎 Suggestion Engine: طلب اقتراح ذكي
        val suggestion = SuggestionEngine.generateSuggestion(context)
        
        suggestion?.let { text ->
            // تمرير الاقتراح عبر الجسر إلى الـ Overlay
            OverlayBridge.showSuggestion(text, context)
        }
    }

    override fun onInterrupt() {}

    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return "No Context"
        val builder = StringBuilder()
        traverseNode(root, builder)
        
        val screenText = builder.toString().replace(Regex("\\s+"), " ").trim()
        val truncatedText = if (screenText.length > 6000) screenText.take(6000) else screenText
        
        val packageName = root.packageName?.toString() ?: "Unknown"
        val category = ContextClassifier.getAppCategory(packageName)

        val finalContext = """
            [App Category: $category]
            [App Package: $packageName]
            [Screen Content: $truncatedText]
        """.trimIndent()

        ScreenContextHolder.lastScreenText = finalContext
        return finalContext
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return
        node.text?.let { if (it.isNotBlank()) builder.append(it).append("\n") }
        node.contentDescription?.let { if (it.isNotBlank()) builder.append(it).append("\n") }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), builder)
        }
    }

    override fun onDestroy() {
        ScreenContextHolder.serviceInstance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
