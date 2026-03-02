package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo

object SemanticRanker {

    fun rankEditableNodes(
        nodes: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {

        return nodes
            .filter { it.className?.contains("EditText") == true && it.isEditable }
            .map { node ->
                node to calculateEditableScore(node)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    fun rankActionButton(
        nodes: List<AccessibilityNodeInfo>,
        keywords: List<String>
    ): AccessibilityNodeInfo? {

        return nodes
            .map { node ->
                node to calculateButtonScore(node, keywords)
            }
            .sortedByDescending { it.second }
            .firstOrNull { it.second > 0 }
            ?.first
    }

    private fun calculateEditableScore(
        node: AccessibilityNodeInfo
    ): Int {

        var score = 0

        if (node.isFocused) score += 5
        if (node.isClickable) score += 2
        if (node.isVisibleToUser) score += 3

        val hint = node.hintText?.toString()?.lowercase() ?: ""
        if (hint.contains("message")) score += 5
        if (hint.contains("search")) score += 4

        return score
    }

    private fun calculateButtonScore(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): Int {

        var score = 0

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (k in keywords) {
            if (text.contains(k.lowercase())) score += 10
            if (desc.contains(k.lowercase())) score += 8
        }

        if (node.isClickable) score += 3
        if (node.isVisibleToUser) score += 2

        return score
    }
}
