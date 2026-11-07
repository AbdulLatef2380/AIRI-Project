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
        return if (current.isBlank()) prompt else "$current\n\n$prompt"
    }
}
