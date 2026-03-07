package com.airi.assistant.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

object UITreeScanner {

    private const val TAG = "AIRI_SCANNER"

    /**
     * مسح شجرة الواجهة بالكامل وتحويلها إلى نص، مع حفظ العناصر الهامة في الذاكرة.
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

        val indent = "  ".repeat(depth)

        // 🔍 استخراج البيانات الأساسية للعقدة
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "unknown"
        val viewId = node.viewIdResourceName ?: ""

        // 🎯 تحديد التسمية الأنسب (النص أولاً، ثم الوصف)
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }

        // تحديد الخصائص الوظيفية
        val clickable = if (node.isClickable) "[Clickable]" else ""
        val editable = if (node.isEditable) "[Editable]" else ""

        // 📝 إضافة البيانات لتقرير المسح (Logcat / UI)
        builder.append("$indent$className | Label: $label | ID: $viewId | $clickable $editable\n")

        // ✅ منطق الذاكرة التلقائي المطور (Memory Auto-Learning)
        // نحفظ العنصر إذا كان قابلاً للضغط ويملك اسماً أو معرفاً برمجياً
        if (node.isClickable && (label.isNotEmpty() || viewId.isNotEmpty())) {
            try {
                UIMemory.rememberNode(
                    context,
                    label.ifEmpty { viewId }, // الأولوية للاسم، وإذا غاب نستخدم الـ ID كمرجع
                    className
                )
            } catch (e: Exception) {
                Log.e(TAG, "Memory save error: ${e.message}")
            }
        }

        // الاستمرار في مسح كافة الأبناء (Recursion)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverse(context, child, builder, depth + 1)
            }
        }
    }
}
