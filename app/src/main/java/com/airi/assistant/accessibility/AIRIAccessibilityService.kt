package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.accessibility.OverlayBridge

class AIRIAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
    }

    /**
     * ✅ المحرك الاستباقي المطور مع Debounce Guard
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // تصفية الأحداث: نراقب فقط تغير النافذة أو محتوى العناصر
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // إزالة أي طلب معالجة معلق (Debounce)
        debounceRunnable?.let { handler.removeCallbacks(it) }

        // إنشاء طلب معالجة جديد يبدأ بعد 500 ملي ثانية من الثبات
        debounceRunnable = Runnable {
            processContextChange()
        }

        handler.postDelayed(debounceRunnable!!, 500)
    }

    /**
     * ✅ معالجة التغيير في السياق باستخدام الذكاء الجوهري (Refined Hash)
     */
    private fun processContextChange() {
        // 1. استخراج السياق الحالي
        val context = extractScreenContext()
        
        // 2. حساب الهاش الذكي (يركز على العناوين والكلمات المفتاحية فقط)
        val refinedHash = ContextIntelligence.computeRefinedHash(context)

        // 3. الحارس: إذا لم يتغير "جوهر" الشاشة، توقف هنا
        if (refinedHash == ScreenContextHolder.lastContextHash) return

        // 4. تحديث الهاش الأخير
        ScreenContextHolder.lastContextHash = refinedHash

      val suggestions = SuggestionEngine.generateSuggestions(context)

if (suggestions.isNotEmpty()) {
    OverlayBridge.showSuggestion(suggestions.first(), context)
}  
    }

    override fun onInterrupt() {}

    /**
     * استخراج نص الشاشة بالكامل مع مراعاة حدود الـ Tokens
     */
    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return "No Context"
        val builder = StringBuilder()
        traverseNode(root, builder)
        
        val screenText = builder.toString().replace(Regex("\\s+"), " ").trim()
        val truncatedText = if (screenText.length > 6000) screenText.take(6000) else screenText
        
        val packageName = root.packageName?.toString() ?: "Unknown"
        val category = ContextClassifier.getAppCategory(packageName)
        val className = root.className?.toString() ?: "Unknown"

        val finalContext = """
            [App Category: $category]
            [App Package: $packageName]
            [App Screen: $className]
            [Screen Content: $truncatedText]
        """.trimIndent()

        ScreenContextHolder.lastScreenText = finalContext
        return finalContext
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return
        
        node.text?.let { 
            if (it.isNotBlank()) builder.append(it).append("\n") 
        }
        
        node.contentDescription?.let { 
            if (it.isNotBlank()) builder.append(it).append("\n") 
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, builder)
                child.recycle() // تنظيف الذاكرة فوراً لمنع البطء
            }
        }
    }

    override fun onDestroy() {
        ScreenContextHolder.serviceInstance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
