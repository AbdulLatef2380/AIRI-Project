package com.airi.assistant.execution.cloud

import org.json.JSONObject

/**
 * Parses one Gemini SSE data payload without relying on JSON whitespace or key order.
 * Gemini may serialize the same response as `"finishReason":"STOP"` or
 * `"finishReason": "STOP"`; both are valid and must be treated identically.
 */
internal object GeminiSseParser {
    data class Event(
        val text: String = "",
        val terminal: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val hasError: Boolean = false,
    )

    fun parse(payload: String): Event {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return Event()
        if (root.has("error")) return Event(hasError = true)

        val candidates = root.optJSONArray("candidates")
        var text = buildString {
            if (candidates != null) {
                for (i in 0 until candidates.length()) {
                    val candidate = candidates.optJSONObject(i) ?: continue
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        append(part.optString("text", ""))
                    }
                }
            }
        }

        val terminal = if (candidates == null) {
            false
        } else {
            (0 until candidates.length()).any { index ->
                val reason = candidates.optJSONObject(index)?.optString("finishReason", "").orEmpty()
                reason.isNotBlank() && reason != "null"
            }
        }
        val usage = root.optJSONObject("usageMetadata")
        return Event(
            text = text,
            terminal = terminal,
            promptTokens = usage?.optIntOrNull("promptTokenCount"),
            completionTokens = usage?.optIntOrNull("candidatesTokenCount"),
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}
