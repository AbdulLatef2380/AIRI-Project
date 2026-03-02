package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo

object NodeScanner {

    fun collectAllNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return result

        traverse(root, result)
        return result
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        result.add(node)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                traverse(it, result)
            }
        }
    }
}
