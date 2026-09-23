package com.airi.assistant.ai

/** Deterministic language contract used before model routing and prompt construction. */
enum class UserLanguage(val code: String, val displayName: String) {
    ARABIC("ar", "Arabic"),
    ENGLISH("en", "English"),
    MIXED("mixed", "the user's dominant language"),
    UNKNOWN("unknown", "the user's language")
}

object LanguagePolicy {
    private val arabic = Regex("[\\u0600-\\u06FF]")
    private val latin = Regex("[A-Za-z]")

    fun detect(input: String): UserLanguage {
        val arabicCount = arabic.findAll(input).count()
        val latinCount = latin.findAll(input).count()
        return when {
            arabicCount == 0 && latinCount == 0 -> UserLanguage.UNKNOWN
            arabicCount > 0 && latinCount == 0 -> UserLanguage.ARABIC
            latinCount > 0 && arabicCount == 0 -> UserLanguage.ENGLISH
            arabicCount >= latinCount -> UserLanguage.MIXED
            else -> UserLanguage.MIXED
        }
    }

    fun responseInstruction(input: String): String = when (detect(input)) {
        UserLanguage.ARABIC -> "The user's message is Arabic (ar). Reply entirely in Arabic, using Arabic script. Do not answer in English unless the user explicitly asks for translation."
        UserLanguage.ENGLISH -> "The user's message is English (en). Reply entirely in English unless the user explicitly requests another language."
        UserLanguage.MIXED -> "The user's message is mixed Arabic/Latin. Reply in the dominant language of the user's request; preserve quoted code, names, and technical identifiers."
        UserLanguage.UNKNOWN -> "Reply in the language used by the user; never use a generic English fallback when the intent is clear."
    }
}
