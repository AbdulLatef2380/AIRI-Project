package com.airi.assistant.agent.execution.node

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object NodeScanner {

    private const val TAG = "NodeScanner"

    fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes, depth = 0)
        Log.d(TAG, "Collected ${nodes.size} nodes from accessibility tree")
        return nodes
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 50) return
        result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, result, depth + 1)
        }
    }

    fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        return collectAllNodes(root).find { node ->
            node.text?.toString()?.lowercase()?.contains(lower) == true ||
            node.contentDescription?.toString()?.lowercase()?.contains(lower) == true
        }
    }

    fun findClickableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return collectAllNodes(root).filter { it.isClickable }
    }

    fun findEditableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return collectAllNodes(root).filter { it.isEditable }
    }

    fun findScrollableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return collectAllNodes(root).filter { it.isScrollable }
    }
}
