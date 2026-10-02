package com.airi.assistant.ui.screens

import com.airi.assistant.ui.text.AssistantContentClassifier
import com.airi.assistant.ui.text.AssistantContentKind

/** Presentation policy for responses that need a readable, selectable full-screen surface. */
internal object ChatRichContentPolicy {
    fun needsFullscreen(text: String): Boolean =
        when (AssistantContentClassifier.analyse(text).kind) {
            AssistantContentKind.RICH_CONTENT,
            AssistantContentKind.CODE_BLOCK,
            AssistantContentKind.STRUCTURED_CODE,
            AssistantContentKind.TABLE -> true
            else -> false
        }
}
