package com.airi.assistant.ui.viewmodel

/** Builds a bounded, reviewable continuation request without auto-submitting it. */
object ResponseContinuationPolicy {
    const val MAX_SOURCE_CHARS = 8_000
    const val MAX_PROMPT_CHARS = 12_000

    fun canContinue(status: GenerationResponseStatus, partial: String): Boolean =
        partial.isNotBlank() && status == GenerationResponseStatus.INCOMPLETE

    fun buildPrompt(partial: String, reason: String? = null): String? {
        val source = partial.trim().takeLast(MAX_SOURCE_CHARS)
        if (source.isBlank()) return null
        val reasonLine = reason?.takeIf { it.isNotBlank() }?.let { "The previous generation stopped because: ${it.take(120)}.\n" }.orEmpty()
        return ("Continue the previous response from exactly where it stopped. " +
            "Do not repeat any text already present. Preserve unfinished Markdown/code fences and close them correctly. " +
            "Return only the continuation, not a preamble.\n\n" + reasonLine +
            "Previous response ending:\n" + source).take(MAX_PROMPT_CHARS)
    }

    fun merge(previous: String, continuation: String): String {
        val next = continuation.trim()
        if (next.isBlank()) return previous
        val overlap = longestSuffixPrefixOverlap(previous, next)
        return previous + next.drop(overlap)
    }

    private fun longestSuffixPrefixOverlap(previous: String, next: String): Int {
        val max = minOf(previous.length, next.length, 2_000)
        for (length in max downTo 1) {
            if (previous.takeLast(length) == next.take(length)) return length
        }
        return 0
    }
}
