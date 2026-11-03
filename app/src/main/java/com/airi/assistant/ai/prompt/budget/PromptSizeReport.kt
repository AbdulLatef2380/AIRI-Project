package com.airi.assistant.ai.prompt.budget

/**
 * Report 1, item 24 (stage 19): measures what a request carries before the
 * first token, so the on-device numbers can settle the open questions about
 * prompt size and latency. It only records and formats; it changes no behaviour.
 *
 * Sizes are in characters plus a token estimate from [PromptBudgetLedger].
 * Timings come from an injectable clock so the formatting is unit-testable.
 */
class PromptSizeReport {
    data class Part(val name: String, val chars: Int, val counted: Boolean = true) {
        val estimatedTokens: Int get() = (chars.coerceAtLeast(0) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    private val parts = mutableListOf<Part>()

    /** Records one contributor. Blank or empty contributors are kept with 0 so absence is visible. */
    fun add(name: String, text: String?): PromptSizeReport = addChars(name, text?.length ?: 0)

    fun addChars(name: String, chars: Int): PromptSizeReport {
        parts += Part(name, chars.coerceAtLeast(0))
        return this
    }

    /**
     * Records a contributor that is already inside another counted part (for
     * example RAG text inside the base system prompt). It is printed in
     * brackets and left out of the total so nothing is counted twice.
     */
    fun addIncluded(name: String, text: String?): PromptSizeReport {
        parts += Part(name, text?.length ?: 0, counted = false)
        return this
    }

    val totalChars: Int get() = parts.filter { it.counted }.sumOf { it.chars }
    val totalEstimatedTokens: Int get() = (totalChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun snapshot(): List<Part> = parts.toList()

    /** `base=3120c,tools=980c,history=4410c,[rag=0c] total=8510c/~2128t` */
    fun toLogLine(): String =
        parts.joinToString(",") { if (it.counted) "${it.name}=${it.chars}c" else "[${it.name}=${it.chars}c]" } +
            " total=${totalChars}c/~${totalEstimatedTokens}t"

    private companion object {
        const val CHARS_PER_TOKEN = 4
    }
}

/**
 * Wall-clock marks between "send accepted" and "first token". Each mark stores
 * the time since the previous mark, so a slow stage is visible by itself rather
 * than hidden in a running total.
 */
class PreFirstTokenTimeline(private val clockMs: () -> Long = System::currentTimeMillis) {
    private val startMs = clockMs()
    private var lastMs = startMs
    private val marks = mutableListOf<Pair<String, Long>>()

    @Synchronized
    fun mark(stage: String) {
        val now = clockMs()
        marks += stage to (now - lastMs).coerceAtLeast(0)
        lastMs = now
    }

    @Synchronized
    fun totalMs(): Long = (lastMs - startMs).coerceAtLeast(0)

    /** `persist=12ms,rag=340ms,prompt=8ms total=360ms` */
    @Synchronized
    fun toLogLine(): String =
        marks.joinToString(",") { "${it.first}=${it.second}ms" } + " total=${totalMs()}ms"
}
