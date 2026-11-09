package com.airi.assistant.domain.prompt

/** Prevents the same capability inventory from being described twice to a model. */
object PromptCapabilityBlockPolicy {
    private const val STRUCTURED_MARKER = "AVAILABLE TOOLS:"
    private const val NARRATIVE_MARKER = "You have access to the following real tools."

    fun narrativeBlockIfNeeded(existingPrompt: String, narrativeBlock: String): String {
        if (narrativeBlock.isBlank()) return ""
        if (existingPrompt.contains(STRUCTURED_MARKER) || existingPrompt.contains(NARRATIVE_MARKER)) return ""
        return narrativeBlock
    }
}
