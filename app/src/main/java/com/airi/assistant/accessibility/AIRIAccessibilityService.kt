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
        // نعتمد على الاستخراج عند الطلب (On-Demand)
    }

    override fun onInterrupt() {}

    // الدالة الجديدة والمطورة
    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return "No active window context found."

        val builder = StringBuilder()
        traverseNode(root, builder)

        val screenText = builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()

        val packageName = root.packageName?.toString() ?: "Unknown"
        val className = root.className?.toString() ?: "Unknown"
        
        // استخدام المصنف الذكي (Classifier)
        val category = ContextClassifier.getAppCategory(packageName)

        val finalContext = """
            [App Category: $category]
            [App Package: $packageName]
            [App Screen: $className]
            [Screen Content: $screenText]
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
            traverseNode(node.getChild(i), builder)
        }
    }
}
