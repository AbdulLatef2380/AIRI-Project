package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.OverlayService

class AIRIAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
    }

    override fun onDestroy() {
        ScreenContextHolder.serviceInstance = null
        super.onDestroy()
    }

    /**
     * ✅ الخطوة الاحترافية: التفعيل الاستباقي (Proactive Trigger)
     * يتم استدعاؤها عند أي تغيير في واجهة النظام أو التطبيقات
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // نراقب فقط تغير حالة النافذة (فتح تطبيق جديد أو نشاط جديد) لتقليل الضغط
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // 1. استخراج السياق الجديد فوراً
            val newContext = extractScreenContext()
            
            // 2. إبلاغ الـ OverlayService بوجود سياق جديد للبحث عن اقتراحات
            val intent = Intent(this, OverlayService::class.java).apply {
                action = "ACTION_SHOW_SUGGESTION"
                putExtra("EXTRA_CONTEXT", newContext)
            }
            
            // نرسل الإشارة للخدمة (تعمل في الخلفية)
            startService(intent)
        }
    }

    override fun onInterrupt() {}

    /**
     * الدالة المطورة لاستخراج السياق مع حماية الـ Tokens (Token Guard)
     */
    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return "No active window context found."

        val builder = StringBuilder()
        traverseNode(root, builder)

        val screenText = builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()

        // 🛡️ Token Guard: تقليم النص لضمان سرعة الاستجابة ومنع الـ Context Overflow
        val truncatedText = if (screenText.length > 6000) {
            screenText.take(6000) + "... [تم قص النص للحفاظ على الأداء]"
        } else {
            screenText
        }

        val packageName = root.packageName?.toString() ?: "Unknown"
        val className = root.className?.toString() ?: "Unknown"
        
        // استخدام المصنف الذكي لتحديد نوع التطبيق
        val category = ContextClassifier.getAppCategory(packageName)

        val finalContext = """
            [App Category: $category]
            [App Package: $packageName]
            [App Screen: $className]
            [Screen Content: $truncatedText]
        """.trimIndent()

        // تحديث الحامل بالسياق الجديد ليكون متاحاً عند الطلب اليدوي أيضاً
        ScreenContextHolder.lastScreenText = finalContext
        return finalContext
    }

    /**
     * دالة Recursive للمرور على جميع عناصر الشاشة واستخراج النصوص
     */
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
                // تنظيف الذاكرة بعد الاستخدام (مهم لمنع Leak في خدمات الوصول)
                child.recycle()
            }
        }
    }
}
