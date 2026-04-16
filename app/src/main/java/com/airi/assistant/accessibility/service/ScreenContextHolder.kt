package com.airi.assistant.accessibility.service

import android.accessibilityservice.AccessibilityService

object ScreenContextHolder {

    @Volatile
    var serviceInstance: AccessibilityService? = null

    val isConnected: Boolean
        get() = serviceInstance != null

    /**
     * Triggers text extraction from the current active window.
     * Returns the visible text of all nodes in the active window as a String.
     * Returns an empty string if the service is not connected or no window is active.
     */
    fun triggerExtraction(): String {
        val service = serviceInstance ?: return ""
        val root = service.rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        extractText(root, sb)
        return sb.toString().trim()
    }

    private fun extractText(node: android.view.accessibility.AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), sb)
        }
    }
}
