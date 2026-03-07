package com.airi.assistant.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.airi.assistant.brain.UIMemory

object UITreeScanner {

    private const val TAG = "AIRI_SCANNER"
    private const val MAX_DEPTH = 25

    fun scanTree(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()
        scanNode(node, builder)
        return builder.toString()
    }

    private fun scanNode(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            builder.append(it).append(" ")
        }

        node.contentDescription?.let {
            builder.append(it).append(" ")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                scanNode(it, builder)
            }
        }
    }

    fun scan(context: Context, root: AccessibilityNodeInfo?): String {
        if (root == null) return ""

        val builder = StringBuilder()
        traverse(context, root, builder, 0)

        return builder.toString()
    }

    private fun traverse(
        context: Context,
        node: AccessibilityNodeInfo?,
        builder: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > MAX_DEPTH) return

        val indent = "  ".repeat(depth)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "unknown"
        val viewId = node.viewIdResourceName ?: ""

        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }

        val clickable = if (node.isClickable) "[C]" else ""
        val editable = if (node.isEditable) "[E]" else ""

        builder.append("$indent$className [$label] {$viewId} $clickable $editable\n")

        if (node.isClickable && (label.length > 2 || viewId.isNotEmpty())) {
            try {
                UIMemory.rememberNode(
                    context,
                    label.ifEmpty { viewId },
                    viewId.ifEmpty { className }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Learning error: ${e.message}")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverse(context, child, builder, depth + 1)
                child.recycle()
            }
        }
    }
}
