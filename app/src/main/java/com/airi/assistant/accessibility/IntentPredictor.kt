package com.airi.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

object IntentPredictor {

    private const val TAG = "AIRI_INTENT"

    private val synonyms = mapOf(
        "search" to listOf("search", "بحث", "find", "lookup"),
        "send" to listOf("send", "ارسال", "submit", "share"),
        "back" to listOf("back", "رجوع", "return"),
        "menu" to listOf("menu", "القائمة", "options"),
        "settings" to listOf("settings", "اعدادات", "preferences")
    )

    fun predictNode(root: AccessibilityNodeInfo?, intent: String): AccessibilityNodeInfo? {

        if (root == null) return null

        val normalizedIntent = intent.lowercase().trim()

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        traverse(root, normalizedIntent, candidates)

        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates for intent: $intent")
            return null
        }

        val best = candidates.maxByOrNull { it.second }

        Log.i(TAG, "Best candidate score: ${best?.second}")

        return best?.first
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        intent: String,
        candidates: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {

        if (node == null) return

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        var score = 0

        if (text.contains(intent)) score += 5
        if (desc.contains(intent)) score += 4
        if (id.contains(intent)) score += 3

        synonyms[intent]?.forEach { syn ->
            if (text.contains(syn)) score += 3
            if (desc.contains(syn)) score += 2
            if (id.contains(syn)) score += 1
        }

        if (node.isClickable) score += 1

        if (score > 0) {
            candidates.add(Pair(node, score))
        }

        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), intent, candidates)
        }
    }
}
