package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.agent.reinforcement.AdaptivePolicy

object SemanticRanker {

    /**
     * اختيار أفضل حقل إدخال مع مراعاة السياق الحالي (التطبيق + الشاشة)
     */
    fun rankEditableNodes(
        nodes: List<AccessibilityNodeInfo>,
        context: String // 🔥 تمرير السياق
    ): AccessibilityNodeInfo? {
        return nodes
            .filter { it.className?.contains("EditText") == true && it.isEditable }
            .map { node ->
                node to calculateEditableScore(node, context)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /**
     * اختيار أفضل زر إجراء مع مراعاة السياق الحالي (التطبيق + الشاشة)
     */
    fun rankActionButton(
        nodes: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        context: String // 🔥 تمرير السياق
    ): AccessibilityNodeInfo? {
        return nodes
            .map { node ->
                node to calculateButtonScore(node, keywords, context)
            }
            .sortedByDescending { it.second }
            .firstOrNull { it.second > 0 }
            ?.first
    }

    /**
     * حساب نقاط حقول الإدخال بناءً على السمات الفيزيائية + الذاكرة السياقية
     */
    private fun calculateEditableScore(
        node: AccessibilityNodeInfo,
        context: String
    ): Int {
        var score = 0

        if (node.isFocused) score += 5
        if (node.isClickable) score += 2
        if (node.isVisibleToUser) score += 3

        val hint = node.hintText?.toString()?.lowercase() ?: ""
        if (hint.contains("message")) score += 5
        if (hint.contains("search")) score += 4

        val key = "editable_${hint}_${node.viewIdResourceName}"
        
        // تعديل النتيجة بناءً على سياق التطبيق الحالي
        return AdaptivePolicy.adjustScore(score, context, key)
    }

    /**
     * حساب نقاط الأزرار بناءً على الكلمات المفتاحية + الذاكرة السياقية
     */
    private fun calculateButtonScore(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        context: String
    ): Int {
        var score = 0

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        // 1. فحص الكلمات المفتاحية دلالياً
        for (k in keywords) {
            if (text.contains(k.lowercase())) score += 10
            if (desc.contains(k.lowercase())) score += 8
        }

        // 2. فحص الخصائص التفاعلية
        if (node.isClickable) score += 3
        if (node.isVisibleToUser) score += 2

        // 3. مفتاح العقدة الفريد للتعلم
        val key = "button_${text}_${desc}"

        // 4. 🔥 القفزة النوعية: تعديل النقاط بناءً على خبرة AIRI في هذا "السياق" تحديداً
        return AdaptivePolicy.adjustScore(score, context, key)
    }
}
