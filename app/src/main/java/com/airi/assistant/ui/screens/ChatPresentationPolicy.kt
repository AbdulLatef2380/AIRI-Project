package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.LayoutDirection
import com.airi.assistant.ui.text.LanguageRuntimeManager

/** Pure presentation rules shared by the chat UI and its unit tests. */
internal object ChatPresentationPolicy {
    enum class BubbleEdge { START, END }
    enum class MessageLength { SHORT, MEDIUM, LONG, VERY_LONG }

    fun textDirection(text: String): LayoutDirection =
        LanguageRuntimeManager.toLayoutDirection(LanguageRuntimeManager.analyseDirection(text))

    fun classifyLength(text: String): MessageLength = when {
        text.length > 4000 -> MessageLength.VERY_LONG
        text.length > 200 -> MessageLength.LONG
        text.length > 40 -> MessageLength.MEDIUM
        else -> MessageLength.SHORT
    }

    /** Maximum user-turn width as a fraction of the available chat width. */
    fun userBubbleFraction(text: String): Float = 0.82f

    /**
     * AIRI's chat convention is physical: user turns stay on the right and
     * assistant turns stay on the left in both locales. The message content
     * itself is still given [LayoutDirection.Rtl] when Arabic is detected.
     * Keeping these concerns separate prevents RTL from swapping chat roles.
     */
    fun bubbleEdge(
        isUser: Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): BubbleEdge = when (layoutDirection) {
        LayoutDirection.Ltr, LayoutDirection.Rtl ->
            if (isUser) BubbleEdge.END else BubbleEdge.START
    }

    fun horizontalArrangement(
        isUser: Boolean,
        layoutDirection: LayoutDirection,
    ): Arrangement.Horizontal = when (bubbleEdge(isUser, layoutDirection)) {
        BubbleEdge.START -> Arrangement.Absolute.Left
        BubbleEdge.END -> Arrangement.Absolute.Right
    }

    fun humanModelLabel(rawId: String, isLocal: Boolean, isCloud: Boolean): String {
        if (rawId.isBlank()) return if (isLocal) "Local" else if (isCloud) "Cloud" else "Auto"
        val normalized = rawId.lowercase()
        return when {
            isLocal -> "Local Llama"
            normalized.contains("gemini") -> "Gemini Flash"
            normalized.contains("claude") -> "Claude"
            normalized.contains("llama") -> "Local Llama"
            normalized.contains("openai") || normalized.contains("gpt") -> "OpenAI"
            isCloud -> "Cloud"
            else -> "Auto"
        }
    }
}

internal fun chatTextDirection(text: String): LayoutDirection =
    ChatPresentationPolicy.textDirection(text)
