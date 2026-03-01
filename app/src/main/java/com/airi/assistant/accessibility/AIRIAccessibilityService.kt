package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AIRIAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
    }

    override fun onDestroy() {
        ScreenContextHolder.serviceInstance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // نعتمد على الاستخراج عند الطلب (On-Demand) لتقليل استهلاك البطارية
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

        // تحديث الحامل بالسياق الجديد
        ScreenContextHolder.lastScreenText = finalContext
        return finalContext
    }

    /**
     * دالة递归 (Recursive) للمرور على جميع عناصر الشاشة واستخراج النصوص
     */
    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return

        // سحب النصوص الظاهرة
        node.text?.let {
            if (it.isNotBlank()) builder.append(it).append("\n")
        }

        // سحب وصف المحتوى (مهم للأيقونات والأزرار بدون نص)
        node.contentDescription?.let {
            if (it.isNotBlank()) builder.append(it).append("\n")
        }

        // الانتقال للأبناء
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, builder)
            }
        }
    }
}
