package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo

object NodeMatcher {

    fun findEditableNode(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        return nodes.firstOrNull {
            it.className?.contains("EditText") == true && it.isEditable
        }
    }

    fun findButtonByText(
        nodes: List<AccessibilityNodeInfo>,
        keyword: String
    ): AccessibilityNodeInfo? {

        return nodes.firstOrNull {
            it.text?.toString()?.contains(keyword, true) == true ||
            it.contentDescription?.toString()?.contains(keyword, true) == true
        }
    }
}
