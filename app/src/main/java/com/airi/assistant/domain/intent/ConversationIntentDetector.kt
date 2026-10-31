package com.airi.assistant.domain.intent

/** Shared, punctuation-tolerant conversation detector used by classification and titles. */
object ConversationIntentDetector {
    private val greetingPrefixes = listOf(
        "hello", "hi", "hey", "good morning", "good evening", "good afternoon",
        "مرحبا", "مرحباً", "اهلا", "أهلا", "اهلاً", "أهلاً", "هلا",
        "السلام عليكم", "صباح الخير", "مساء الخير", "تحياتي", "كيف حالك", "شلونك",
        "thank you", "thanks", "شكرا", "شكرًا", "تمام", "موافق", "ok", "okay",
    )

    fun isGreeting(input: String): Boolean {
        val normalized = input
            .trim()
            .lowercase()
            .replace(Regex("[,،.!؟?؛:—-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return greetingPrefixes.any { normalized == it || normalized.startsWith("$it ") }
    }
}
