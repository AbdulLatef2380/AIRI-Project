package com.airi.assistant.agent.node

import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.agent.decision.DecisionEngine // 🔥 استيراد محرك اتخاذ القرار
import com.airi.assistant.agent.reinforcement.AdaptivePolicy

object SemanticRanker {

    /**
     * اختيار أفضل حقل إدخال بناءً على إستراتيجية ε-Greedy (Exploration vs Exploitation)
     */
    fun rankEditableNodes(
        nodes: List<AccessibilityNodeInfo>,
        context: String
    ): AccessibilityNodeInfo? {
        
        val scored = nodes
            .filter { it.className?.contains("EditText") == true && it.isEditable }
            .map { node ->
                node to calculateEditableScore(node, context)
            }

        // استخدام المحرك لاتخاذ القرار: هل نلتزم بالأفضل أم نجرب شيئاً جديداً؟
        return DecisionEngine.select(scored)
    }

    /**
     * اختيار أفضل زر بناءً على إستراتيجية ε-Greedy (Exploration vs Exploitation)
     */
    fun rankActionButton(
        nodes: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        context: String
    ): AccessibilityNodeInfo? {

        val scoredButtons = nodes.map { node ->
            node to calculateButtonScore(node, keywords, context)
        }

        // اختيار الزر المناسب للسياق مع احتمالية الاستكشاف
        return DecisionEngine.select(scoredButtons)
    }

    /**
     * حساب النقاط الأساسية للحقول مع تعديل Reinforcement السياقي
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
        
        return AdaptivePolicy.adjustScore(score, context, key)
    }

    /**
     * حساب النقاط الأساسية للأزرار مع تعديل Reinforcement السياقي
     */
    private fun calculateButtonScore(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        context: String
    ): Int {
        var score = 0

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (k in keywords) {
            if (text.contains(k.lowercase())) score += 10
            if (desc.contains(k.lowercase())) score += 8
        }

        if (node.isClickable) score += 3
        if (node.isVisibleToUser) score += 2

        val key = "button_${text}_${desc}"

        return AdaptivePolicy.adjustScore(score, context, key)
    }
}
