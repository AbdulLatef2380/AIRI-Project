package com.airi.assistant.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.airi.assistant.brain.UIMemory

object UITreeScanner {

    private const val TAG = "AIRI_SCANNER"

    /**
     * مسح شجرة الواجهة بالكامل وتحويلها إلى نص، مع حفظ العناصر المكتشفة في الذاكرة تلقائياً.
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

        // 🔍 1. استخراج البيانات الخام للعنصر
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "unknown"
        val viewId = node.viewIdResourceName ?: ""

        // 🎯 2. تحديد "التسمية" الأنسب للزر (نص، وصف، أو معرف برمجي)
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }

        val clickable = if (node.isClickable) "[Clickable]" else ""
        val editable = if (node.isEditable) "[Editable]" else ""

        // 📝 3. بناء تقرير المسح النصي (للفهم البرمجي والـ Log)
        builder.append("$indent$className | Label: $label | ID: $viewId | $clickable $editable\n")

        // ✅ 4. منطق التعلم التلقائي (Auto-Learning)
        // وظيفته فقط "الحفظ" في الذاكرة، ولا يتخذ أي قرار تنفيذ هنا.
        if (node.isClickable && (label.isNotEmpty() || viewId.isNotEmpty())) {
            try {
                UIMemory.rememberNode(
                    context,
                    label.ifEmpty { viewId },           // المفتاح (ماذا نسمي هذا الزر؟)
                    viewId.ifEmpty { className }        // القيمة (كيف نضغط عليه لاحقاً؟)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Learning error: ${e.message}")
            }
        }

        // 🔄 5. استمرار المسح لكل الأبناء (Recursion)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverse(context, child, builder, depth + 1)
            }
        }
    }
}
