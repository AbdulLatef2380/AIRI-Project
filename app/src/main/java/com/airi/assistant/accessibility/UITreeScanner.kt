package com.airi.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object UITreeScanner {

    fun scan(root: AccessibilityNodeInfo?): String {

        if (root == null) return ""

        val builder = StringBuilder()

        traverse(root, builder, 0)

        return builder.toString()
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        builder: StringBuilder,
        depth: Int
    ) {

        if (node == null) return

        val indent = " ".repeat(depth * 2)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        val className = node.className?.toString() ?: "unknown"

        val clickable = if (node.isClickable) "clickable" else ""
        val editable = if (node.isEditable) "editable" else ""

        builder.append(
            "$indent$className | $text | $desc | $clickable $editable\n"
        )

        for (i in 0 until node.childCount) {

            traverse(
                node.getChild(i),
                builder,
                depth + 1
            )
        }
    }
}
