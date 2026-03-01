package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

object ScreenContextHolder {
    var lastScreenText: String = ""
    var serviceInstance: AIRIAccessibilityService? = null
}

class AIRIAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
    }

    override fun onDestroy() {
        ScreenContextHolder.serviceInstance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
            builder.append(it).append("\n")
        }

        node.contentDescription?.let {
            builder.append(it).append("\n")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                traverseNode(it, builder)
            }
        }
    }
}
