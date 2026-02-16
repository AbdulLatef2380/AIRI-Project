package com.airi.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * "العين الثالثة" لـ AIRI.
 * خدمة الوصول المسؤولة عن فهم سياق الشاشة بشكل غير متطفل.
 */
class AiriAccessibilityService : AccessibilityService() {

    private lateinit var guardianEngine: GuardianEngine

    companion object {
        private var instance: AiriAccessibilityService? = null
        fun getInstance(): AiriAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        guardianEngine = GuardianEngine(this)
        Log.d("AIRI_VISION", "العين الثالثة مفعلة وجاهزة.")
    }

    private var lastScreenHash: Int = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingContextRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. حماية الخصوصية النشطة
        val packageName = event.packageName?.toString() ?: ""
        if (::guardianEngine.isInitialized && guardianEngine.analyzeAppBehavior(packageName, event.eventType)) {
            Log.w("AIRI_GUARDIAN", "سلوك مشبوه تم اكتشافه في: $packageName")
        }

        // 2. تصفية الأحداث (Debounce + Hash) لمنع إغراق النظام (Flood)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            pendingContextRunnable?.let { handler.removeCallbacks(it) }
            pendingContextRunnable = Runnable {
                val currentContext = getScreenContext()
                val currentHash = currentContext.hashCode()
                
                if (currentHash != lastScreenHash) {
                    lastScreenHash = currentHash
                    // إرسال السياق الجديد للناقل المركزي
                    kotlinx.coroutines.MainScope().launch {
                        AiriCore.send(AiriCore.AiriEvent.ScreenContext(currentContext))
                    }
                }
            }
            handler.postDelayed(pendingContextRunnable!!, 1000) // 1 second debounce
        }
    }

    override fun onInterrupt() {}

    /**
     * تحليل محتوى الشاشة الحالي وتحويله إلى وصف نصي (Context Description)
     */
    fun getScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return "الشاشة غير متاحة حالياً."
        val contextBuilder = StringBuilder()
        
        // استخراج اسم التطبيق الحالي
        contextBuilder.append("التطبيق الحالي: ${rootNode.packageName}\n")
        
        // تحليل العناصر النصية الهامة (تجنب البيانات الحساسة)
        parseNodes(rootNode, contextBuilder)
        
        return contextBuilder.toString()
    }

    private fun parseNodes(node: AccessibilityNodeInfo, builder: StringBuilder) {
        // 1. تجنب حقول كلمات المرور (الخصوصية المقدسة)
        if (node.isPassword) return

        // 2. استخراج النصوص الظاهرة
        node.text?.let {
            if (it.isNotBlank()) {
                builder.append("- $it\n")
            }
        }

        // 3. التكرار عبر الأبناء (Recursive parsing)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { parseNodes(it, builder) }
        }
    }
}
