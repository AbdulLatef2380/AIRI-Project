package com.airi.assistant.system

/**
 * Pure language selection rules shared by persistence and locale application.
 * Arabic is the product default; an explicit supported user choice always wins.
 */
internal object LanguageSelectionPolicy {
    const val DEFAULT_LANGUAGE = "ar"

    private val supportedCodes = setOf("ar", "en", "es", "zh")

    fun sanitize(candidate: String?): String =
        candidate?.takeIf { it in supportedCodes } ?: DEFAULT_LANGUAGE
}
