package com.airi.assistant.domain.intent

import com.airi.assistant.ai.QueryType

/** Canonical, non-authorizing description of one chat request. */
data class ChatIntent(
    val kind: Kind,
    val queryType: QueryType,
    val canonicalCapability: String? = null,
    val typedSlots: Map<String, TypedSlot> = emptyMap(),
    val confidence: Confidence,
    val risk: Risk,
    val rationale: String,
    val source: Source = Source.RULES,
) {
    enum class Kind { CONVERSATION, QUESTION, CREATIVE, POSSIBLE_ACTION, CLARIFICATION }
    enum class Confidence { HIGH, MEDIUM, LOW }
    enum class Risk { NONE, READ, SIDE_EFFECT }
    enum class Source { RULES }

    data class TypedSlot(val value: String, val type: SlotType) {
        enum class SlotType { TEXT, TARGET, LOCALE }
    }

    /** Classification never grants execution permission. */
    val isExecutableCandidate: Boolean
        get() = kind == Kind.POSSIBLE_ACTION && canonicalCapability != null && confidence != Confidence.LOW
}

/** Unicode/Arabic normalization that preserves the caller's original text. */
data class NormalizedInput(val original: String, val normalized: String)

object InputNormalizer {
    fun normalize(input: String): NormalizedInput {
        val normalized = input
            .lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()
        return NormalizedInput(input, normalized)
    }
}
