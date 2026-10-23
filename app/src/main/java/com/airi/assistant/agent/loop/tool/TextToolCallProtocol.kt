package com.airi.assistant.agent.loop.tool

import org.json.JSONArray
import org.json.JSONObject
import com.airi.assistant.ai.intent.ToolCall

/**
 * Pure parser for AIRI's provider-neutral text tool-call envelope.
 *
 * The same [ToolCall] model is used for text-protocol and native-provider
 * results after adapters serialize native calls into this envelope.
 */
object TextToolCallProtocol {
    sealed class ParseResult {
        data object NotAToolCall : ParseResult()
        data class Invalid(val reason: ParseFailureReason) : ParseResult()
        data class Call(val name: String, val args: Map<String, String>, val id: String = "") : ParseResult() {
            val toolCall: ToolCall get() = ToolCall(name, args, id)
        }
        data class Calls(val calls: List<ToolCall>) : ParseResult()
    }

    enum class ParseFailureReason {
        NO_CANDIDATE,
        UNTERMINATED_JSON,
        INVALID_JSON,
        MISSING_TOOL_CALL_OBJECT,
        MISSING_TOOL_NAME,
        INVALID_ARGS_OBJECT,
        EMPTY_TOOL_CALLS,
    }

    fun parse(response: String): ParseResult {
        val cleaned = response
            .replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val marker = Regex("\\{\\s*\\\"(?:tool_call|tool_calls)\\\"\\s*:").find(cleaned)
            ?: return ParseResult.NotAToolCall
        val start = marker.range.first
        val end = matchingObjectEnd(cleaned, start)
            ?: return ParseResult.Invalid(ParseFailureReason.UNTERMINATED_JSON)
        val jsonStr = cleaned.substring(start, end + 1)

        return try {
            val root = JSONObject(jsonStr)
            if (root.has("tool_calls")) {
                parseCalls(root.optJSONArray("tool_calls"))
            } else {
                parseSingle(root.optJSONObject("tool_call"))
            }
        } catch (error: IllegalArgumentException) {
            if (error.message == ParseFailureReason.INVALID_ARGS_OBJECT.name) {
                ParseResult.Invalid(ParseFailureReason.INVALID_ARGS_OBJECT)
            } else {
                ParseResult.Invalid(ParseFailureReason.INVALID_JSON)
            }
        } catch (_: Exception) {
            ParseResult.Invalid(ParseFailureReason.INVALID_JSON)
        }
    }

    fun encode(call: ToolCall): String = encode(listOf(call))

    fun encode(calls: List<ToolCall>): String {
        require(calls.isNotEmpty()) { "At least one tool call is required" }
        if (calls.size == 1) {
            val call = calls.single()
            return JSONObject().apply {
                put("tool_call", callToJson(call))
            }.toString()
        }
        return JSONObject().apply {
            put("tool_calls", JSONArray().apply { calls.forEach { put(callToJson(it)) } })
        }.toString()
    }

    private fun parseSingle(toolCall: JSONObject?): ParseResult {
        toolCall ?: return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_CALL_OBJECT)
        val call = parseCall(toolCall) ?: return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_NAME)
        return ParseResult.Call(call.toolName, call.params, call.id)
    }

    private fun parseCalls(array: JSONArray?): ParseResult {
        if (array == null || array.length() == 0) {
            return ParseResult.Invalid(ParseFailureReason.EMPTY_TOOL_CALLS)
        }
        val calls = buildList {
            for (index in 0 until array.length()) {
                val call = array.optJSONObject(index)
                    ?: return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_NAME)
                add(parseCall(call) ?: return ParseResult.Invalid(ParseFailureReason.MISSING_TOOL_NAME))
            }
        }
        return ParseResult.Calls(calls)
    }

    private fun parseCall(toolCall: JSONObject): ToolCall? {
        val name = toolCall.optString("name", "").trim()
        if (name.isBlank()) return null
        val argsObj = toolCall.opt("args")
        if (argsObj != null && argsObj !is JSONObject) {
            throw IllegalArgumentException(ParseFailureReason.INVALID_ARGS_OBJECT.name)
        }
        val argsJson = argsObj as? JSONObject ?: JSONObject()
        val args = buildMap {
            for (key in argsJson.keys()) put(key, argsJson.opt(key)?.toString().orEmpty())
        }
        return ToolCall(name, args, toolCall.optString("id", ""))
    }

    private fun callToJson(call: ToolCall): JSONObject = JSONObject().apply {
        put("name", call.toolName)
        put("args", JSONObject(call.params))
        if (call.id.isNotBlank()) put("id", call.id)
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
