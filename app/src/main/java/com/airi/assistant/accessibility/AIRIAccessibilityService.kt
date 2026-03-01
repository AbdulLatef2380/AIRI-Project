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
        // لا نستخدم event-based extraction حالياً
        // نستخدم On-Demand extraction فقط
    }

    override fun onInterrupt() {}

    fun extractScreenText(): String {
        val root = rootInActiveWindow ?: return ""

        val builder = StringBuilder()
        traverseNode(root, builder)

        val cleanText = builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()

        ScreenContextHolder.lastScreenText = cleanText
        return cleanText
    }

    private fun traverseNode(node: AccessibilityNodeInfo, builder: StringBuilder) {

        node.text?.let {
            if (it.isNotBlank()) {
                builder.append(it).append("\n")
            }
        }

        node.contentDescription?.let {
            if (it.isNotBlank()) {
                builder.append(it).append("\n")
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                traverseNode(it, builder)
            }
        }
    }
}
