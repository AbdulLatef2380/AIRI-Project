package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.agent.reinforcement.AdaptivePolicy // 🔥 استيراد سياسة التكيف

object SemanticRanker {

    /**
     * اختيار أفضل حقل إدخال بناءً على النقاط + التاريخ التكيفي
     */
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

    /**
     * اختيار أفضل زر إجراء بناءً على النقاط + التاريخ التكيفي
     */
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

    /**
     * حساب نقاط حقول الإدخال مع دمج الـ Reinforcement
     */
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

        // 🔥 تطبيق التعديل التكيفي بناءً على نجاح/فشل هذا الحقل سابقاً
        val key = "editable_${hint}_${node.viewIdResourceName}"
        return AdaptivePolicy.adjustScore(score, key)
    }

    /**
     * حساب نقاط الأزرار مع دمج الـ Reinforcement
     */
    private fun calculateButtonScore(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): Int {
        var score = 0

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        // 1. البحث عن الكلمات المفتاحية
        for (k in keywords) {
            if (text.contains(k.lowercase())) score += 10
            if (desc.contains(k.lowercase())) score += 8
        }

        // 2. تقييم الحالة الفيزيائية للعقدة
        if (node.isClickable) score += 3
        if (node.isVisibleToUser) score += 2

        // 3. 🔥 تطبيق الذكاء التكيفي (Reinforcement Adjustment)
        // المفتاح يدمج النص والوصف لتمييز الزر بدقة
        val key = "button_${text}_${desc}"

        return AdaptivePolicy.adjustScore(score, key)
    }
}
