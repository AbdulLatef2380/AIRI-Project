package com.airi.assistant.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

object UITreeScanner {

    private const val TAG = "AIRI_SCANNER"

    /**
     * مسح شجرة الواجهة بالكامل وتحويلها إلى نص، مع حفظ العناصر الهامة في الذاكرة
     */
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
        if (node == null) return

        val indent = "  ".repeat(depth) // تنسيق بصري للشجرة

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "unknown"

        val clickable = if (node.isClickable) "[Clickable]" else ""
        val editable = if (node.isEditable) "[Editable]" else ""

        // إضافة البيانات للنص الممسوح
        builder.append("$indent$className | Text: $text | Desc: $desc | $clickable $editable\n")

        // ✅ إضافة منطق الذاكرة التلقائي (Memory Auto-Learning)
        // إذا كان العنصر قابلاً للضغط وله نص، نحفظه فوراً لنتذكره لاحقاً
        if (node.isClickable && text.isNotEmpty()) {
            try {
                UIMemory.rememberNode(
                    context,
                    text,
                    className
                )
                // Log اختياري للتأكد من الحفظ أثناء التطوير
                // Log.d(TAG, "Memorized node: $text ($className)")
            } catch (e: Exception) {
                Log.e(TAG, "Memory save error", e)
            }
        }

        // الاستمرار في فحص الأبناء (Recursion)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverse(context, child, builder, depth + 1)
        }
    }
}
