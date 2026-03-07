package com.airi.assistant.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.airi.assistant.brain.UIMemory

object UITreeScanner {

    private const val TAG = "AIRI_SCANNER"
    private const val MAX_DEPTH = 25 // 🛠️ تحسين 2: لمنع CPU Spike في الواجهات العميقة

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
        // 🛠️ تحسين 2: التحقق من العمق لمنع استنزاف الموارد
        if (node == null || depth > MAX_DEPTH) return

        val indent = "  ".repeat(depth)

        // 🔍 1. استخراج البيانات الخام للعنصر
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "unknown"
        val viewId = node.viewIdResourceName ?: ""

        // 🎯 2. تحديد التسمية الأنسب (تفضيل النص على الوصف)
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }

        // 🛠️ تحسين إضافي: رموز مختصرة لتقليل حجم النص (Payload Size)
        val clickable = if (node.isClickable) "[C]" else ""
        val editable = if (node.isEditable) "[E]" else ""

        // 📝 3. بناء تقرير المسح النصي بالصيغة المحسنة
        builder.append("$indent$className [$label] {$viewId} $clickable $editable\n")

        // ✅ 4. منطق التعلم التلقائي (Auto-Learning) مع تحسين الفلترة
        // 🛠️ تحسين 3: تخزين فقط العناصر ذات القيمة (تجنب الرموز المفردة أو العناصر الفارغة)
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

        // 🔄 5. استمرار المسح لكل الأبناء مع إدارة الذاكرة
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverse(context, child, builder, depth + 1)
                
                // 🛠️ تحسين 1: إعادة تدوير العقدة لمنع Memory Leak (ضروري جداً)
                child.recycle()
            }
        }
    }
}
