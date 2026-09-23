package com.airi.assistant.ui.screens

/** Presentation policy for responses that need a readable, selectable full-screen surface. */
internal object ChatRichContentPolicy {
    fun needsFullscreen(text: String): Boolean =
        text.length > 600 || text.contains("```") || text.contains("```json") || text.contains("```kotlin")
}
