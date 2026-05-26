package com.airi.assistant.agent.execution.node

import android.view.accessibility.AccessibilityNodeInfo
import com.airi.assistant.agent.learning.reinforcement.AdaptivePolicy

/**
 * SemanticRanker — ε-greedy node selection for the accessibility execution layer.
 *
 * Phase 1 change: DecisionEngine was removed (0 callers in chat path).
 * Its only logic was epsilon-greedy selection — inlined here directly.
 */
object SemanticRanker {

    private const val EPSILON = 0.15

    private fun <T> epsilonGreedySelect(scored: List<Pair<T, Int>>): T? {
        if (scored.isEmpty()) return null
        return if (Math.random() < EPSILON) scored.random().first
               else scored.maxByOrNull { it.second }?.first
    }

    fun rankEditableNodes(nodes: List<AccessibilityNodeInfo>, context: String): AccessibilityNodeInfo? {
        val scored = nodes
            .filter { it.className?.contains("EditText") == true && it.isEditable }
            .map { node -> node to calculateEditableScore(node, context) }
        return epsilonGreedySelect(scored)
    }

    fun rankActionButton(
        nodes: List<AccessibilityNodeInfo>,
        keywords: List<String>,
        context: String
    ): AccessibilityNodeInfo? {
        val scored = nodes.map { node -> node to calculateButtonScore(node, keywords, context) }
        return epsilonGreedySelect(scored)
    }

    private fun calculateEditableScore(node: AccessibilityNodeInfo, context: String): Int {
        var score = 0
        if (node.isFocused)        score += 5
        if (node.isClickable)      score += 2
        if (node.isVisibleToUser)  score += 3
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        if (hint.contains("message")) score += 5
        if (hint.contains("search"))  score += 4
        return AdaptivePolicy.adjustScore(score, context, "editable_${hint}_${node.viewIdResourceName}")
    }

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
        if (node.isClickable)     score += 3
        if (node.isVisibleToUser) score += 2
        return AdaptivePolicy.adjustScore(score, context, "button_${text}_${desc}")
    }
}
