package com.airi.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object NodeFinder {

    fun findFirstClickable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {

        if (root == null) return null

        if (root.isClickable) {
            return root
        }

        for (i in 0 until root.childCount) {

            val result = findFirstClickable(root.getChild(i))

            if (result != null) return result
        }

        return null
    }

    fun findClickableByIndex(
        root: AccessibilityNodeInfo?,
        index: Int
    ): AccessibilityNodeInfo? {

        val list = mutableListOf<AccessibilityNodeInfo>()

        collectClickable(root, list)

        if (index < list.size) {
            return list[index]
        }

        return null
    }

    private fun collectClickable(
        node: AccessibilityNodeInfo?,
        list: MutableList<AccessibilityNodeInfo>
    ) {

        if (node == null) return

        if (node.isClickable) {
            list.add(node)
        }

        for (i in 0 until node.childCount) {

            collectClickable(
                node.getChild(i),
                list
            )
        }
    }
}
