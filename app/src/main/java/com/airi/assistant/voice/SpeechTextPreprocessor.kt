package com.airi.assistant.voice

/**
 * Converts assistant output into speakable text without changing the visible
 * answer. It removes presentation-only markup, protects URLs from being read
 * as raw punctuation, and keeps Arabic/English mixed text intact.
 */
object SpeechTextPreprocessor {
    private val fencedCode = Regex("```[\\s\\S]*?```")
    private val markdownLink = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    private val markdownDecoration = Regex("[*_~`#>]")
    private val url = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val repeatedWhitespace = Regex("[ \\t]{2,}")

    fun prepare(input: String): String {
        if (input.isBlank()) return ""
        var text = input
            .replace(fencedCode, " ")
            .replace(markdownLink) { it.groupValues[1] }
            .replace(url, " رابط خارجي ")
            .replace(markdownDecoration, "")
            .replace(Regex("\\r?\\n{3,}"), "\\n\\n")
            .replace(repeatedWhitespace, " ")
            .trim()
        // Do not vocalize hidden/system-like prefixes that may appear in a
        // provider response. Keep the user's actual content untouched.
        text = text.replace(Regex("(?im)^\\s*(result|output|assistant)\\s*:\\s*"), "")
        return text.trim()
    }
}
