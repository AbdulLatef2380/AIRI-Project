package com.airi.assistant.ui.screens

/**
 * Keeps attachment-menu actions distinct from an ordinary chat turn.
 *
 * The menu remains a UI affordance, while the resulting prompt is the explicit
 * request contract consumed by AIRI's normal agent pipeline. This avoids the
 * old behaviour where some actions inserted only a label and lost the user's
 * intended workflow.
 */
object AttachmentActionPromptPolicy {
    fun merge(existing: String, actionPrompt: String): String {
        val prompt = actionPrompt.trim()
        if (prompt.isBlank()) return existing
        val current = existing.trimEnd()
        if (current.isBlank()) return prompt

        // A menu tap is a UI selection, not a new user turn. Repeated taps on
        // the same shortcut must not keep appending the same instruction to the
        // draft (and later to durable chat history). Keep distinct actions
        // composable so a user can intentionally combine multiple requests.
        val paragraphs = current
            .split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return if (prompt in paragraphs) current else "$current\n\n$prompt"
    }
}
