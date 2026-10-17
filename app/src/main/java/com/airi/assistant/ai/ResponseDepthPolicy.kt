package com.airi.assistant.ai

/**
 * Pure response-shape policy. QueryType describes routing and authorization;
 * it must not force every read-only question into a two-sentence answer.
 */
enum class ResponseDepth {
    BRIEF,
    STANDARD,
    DETAILED,
}

object ResponseDepthPolicy {
    private val detailMarkers = listOf(
        "explain", "describe", "why", "how", "compare", "difference",
        "pros and cons", "step by step", "in detail", "elaborate",
        "اشرح", "وضح", "وضّح", "لماذا", "كيف", "قارن", "الفرق",
        "خطوة بخطوة", "بالتفصيل", "حلل", "حلّل",
    )

    private val openEndedMarkers = listOf(
        "what can you do", "what are your capabilities", "tell me about",
        "give me an overview", "walk me through", "ما الذي تستطيع",
        "ماذا تستطيع", "ما هي قدراتك", "حدثني عن", "أعطني نظرة عامة",
    )

    fun resolve(input: String, queryType: QueryType): ResponseDepth {
        val text = input.trim().lowercase()
        if (text.isBlank()) return ResponseDepth.STANDARD
        if (openEndedMarkers.any(text::contains) || detailMarkers.any(text::contains)) {
            return ResponseDepth.DETAILED
        }
        if (queryType == QueryType.ANALYTICAL || queryType == QueryType.CREATIVE) {
            return ResponseDepth.DETAILED
        }
        val sentenceCount = text.count { it == '.' || it == '?' || it == '؟' || it == '\n' }
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return when {
            sentenceCount >= 2 || wordCount >= 28 -> ResponseDepth.DETAILED
            queryType == QueryType.SIMPLE && wordCount <= 8 -> ResponseDepth.BRIEF
            else -> ResponseDepth.STANDARD
        }
    }
}
