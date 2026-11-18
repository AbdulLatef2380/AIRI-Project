package com.airi.assistant.ui.text

/**
 * Keeps the expensive Markdown parser away from the incomplete streaming tail.
 * A line is considered stable only after a newline has arrived; the final line
 * is rendered as ordinary text until the response completes.
 */
object StreamingMarkdownPolicy {
    data class Split(val stablePrefix: String, val tail: String)

    fun split(text: String): Split {
        if (text.isEmpty()) return Split("", "")
        val boundary = text.lastIndexOf('\n')
        if (boundary < 0) return Split("", text)
        return Split(
            stablePrefix = text.substring(0, boundary + 1),
            tail = text.substring(boundary + 1),
        )
    }
}
