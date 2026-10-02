package com.airi.assistant.agent.loop.tool

import org.json.JSONObject

/**
 * Pure parser for AIRI's existing text tool-call protocol.
 * It does not dispatch, validate permissions, or execute tools.
 */
object TextToolCallProtocol {

    sealed class ParseResult {
        data object NotAToolCall : ParseResult()
        data class Invalid(val reason: ParseFailureReason) : ParseResult()
        data class Call(val name: String, val args: Map<String, String>) : ParseResult()
    }

    enum class ParseFailureReason {
        NO_CANDIDATE,
        UNTERMINATED_JSON,
        INVALID_JSON,
        MISSING_TOOL_CALL_OBJECT,
        MISSING_TOOL_NAME,
        INVALID_ARGS_OBJECT,
    }

    fun parse(response: String): ParseResult {
        val cleaned = response
            .replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val marker = Regex("\\{\\s*\\\"tool_call\\\"\\s*:").find(cleaned)
            ?: return ParseResult.NotAToolCall
        val start = marker.range.first
        val end = matchingObjectEnd(cleaned, start)
            ?: return ParseResult.Invalid(ParseFailureReason.UNTERMINATED_JSON)
        val jsonStr = cleaned.substring(start, end + 1)

        return try {
            val root = JSONObject(jsonStr)
            val toolCall = root.optJSONObject("tool_call")
                ?: return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_CALL_OBJECT)
            val name = toolCall.optString("name", "").trim()
            if (name.isBlank()) return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_NAME)
            val argsObj = toolCall.opt("args")
            if (argsObj != null && argsObj !is JSONObject) {
                return ParseResult.Invalid(ParseFailureReason.INVALID_ARGS_OBJECT)
            }
            val argsJson = argsObj as? JSONObject ?: JSONObject()
            val args = buildMap {
                for (key in argsJson.keys()) put(key, argsJson.opt(key)?.toString().orEmpty())
            }
            ParseResult.Call(name, args)
        } catch (_: Exception) {
            ParseResult.Invalid(ParseFailureReason.INVALID_JSON)
        }
    }

    private fun matchingObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }
}
