package com.airi.assistant.ui.screens

import androidx.compose.ui.unit.LayoutDirection
import com.airi.assistant.ui.text.LanguageRuntimeManager

/** Pure presentation rules shared by the chat UI and its unit tests. */
internal object ChatPresentationPolicy {
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

    fun humanModelLabel(rawId: String, isLocal: Boolean, isCloud: Boolean): String {
        if (rawId.isBlank()) return if (isLocal) "Local" else if (isCloud) "Cloud" else "Auto"
        val normalized = rawId.lowercase()
        return when {
            normalized.contains("gemini") -> "Gemini Flash"
            normalized.contains("claude") -> "Claude"
            normalized.contains("llama") -> "Local Llama"
            normalized.contains("openai") || normalized.contains("gpt") -> "OpenAI"
            isLocal -> "Local"
            isCloud -> "Cloud"
            else -> "Auto"
        }
    }
}

internal fun chatTextDirection(text: String): LayoutDirection =
    ChatPresentationPolicy.textDirection(text)
