package com.airi.core.memory.text

object MemoryTextNormalizer {

    private val separators = Regex("[^\\p{L}\\p{Nd}]+")
    private val arabicMarks = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun tokens(value: String): Set<String> = value
        .lowercase()
        .replace(arabicMarks, "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ى', 'ي')
        .split(separators)
        .asSequence()
        .map(::normalizeToken)
        .filter { it.length > 1 }
        .toSet()

    private fun normalizeToken(token: String): String = when {
        token.length > 4 && token.startsWith("ال") -> token.removePrefix("ال")
        else -> token
    }
}
