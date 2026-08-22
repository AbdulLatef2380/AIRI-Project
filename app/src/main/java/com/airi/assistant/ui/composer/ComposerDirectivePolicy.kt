package com.airi.assistant.ui.composer

/**
 * Keeps the user's task text when a leading skill/knowledge suggestion is
 * selected. The directive query is replaced, while text after its first space
 * remains part of the message.
 */
object ComposerDirectivePolicy {
    fun applySelection(currentText: String, directiveId: String, isKnowledge: Boolean): String {
        val trimmed = currentText.trimStart()
        val suffix = trimmed.substringAfterFirstWhitespace().trimStart()
        val directive = if (isKnowledge) "@knowledge:$directiveId" else "/skill:$directiveId"
        return if (suffix.isBlank()) "$directive " else "$directive $suffix"
    }

    private fun String.substringAfterFirstWhitespace(): String {
        val index = indexOfFirst { it.isWhitespace() }
        return if (index < 0) "" else substring(index + 1)
    }
}
