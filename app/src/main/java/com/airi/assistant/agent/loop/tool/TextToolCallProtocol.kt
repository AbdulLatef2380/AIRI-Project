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
        MIXED_PROSE,
        MISSING_TOOL_CALL_OBJECT,
        MISSING_TOOL_NAME,
        INVALID_ARGS_OBJECT,
        EMPTY_TOOL_CALLS,
        UNEXPECTED_ENVELOPE_CONTENT,
    }

    private val envelopeFenceRegex = Regex(
        "^```([A-Za-z0-9_+\\-]*)[ \\t]*\\r?\\n(.*?)\\r?\\n```$",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Official extraction contract for the text protocol:
     *
     * 1. Accept one complete JSON object whose only top-level member is
     *    `tool_call` or `tool_calls`.
     * 2. Also accept that same object in one `json` or untagged fenced block.
     * 3. Reject every prefix/suffix, including prose, a second JSON value, or
     *    another fenced block. Native provider payloads are not extracted here;
     *    callers must keep using their provider adapter first.
     */
    private fun extractEnvelope(response: String): String? {
        val trimmed = response.trim()
        if (!trimmed.contains("```")) return trimmed
        val match = envelopeFenceRegex.matchEntire(trimmed) ?: return null
        val language = match.groupValues[1].lowercase()
        if (language.isNotEmpty() && language != "json") return null
        return match.groupValues[2].trim()
    }

    fun parse(response: String): ParseResult {
        val hasEnvelopeMarker = response.contains("\"tool_call\"") || response.contains("\"tool_calls\"")
        val extracted = extractEnvelope(response)
            ?: return if (hasEnvelopeMarker) {
                ParseResult.Invalid(ParseFailureReason.MIXED_PROSE)
            } else {
                ParseResult.NotAToolCall
            }
        if (extracted.isBlank()) return ParseResult.NotAToolCall
        if (extracted.startsWith("{") && !extracted.endsWith("}")) {
            val lastClosedObject = extracted.lastIndexOf('}')
            return if (hasEnvelopeMarker && lastClosedObject >= 0 && extracted.substring(lastClosedObject + 1).isNotBlank()) {
                ParseResult.Invalid(ParseFailureReason.MIXED_PROSE)
            } else if (hasEnvelopeMarker) {
                ParseResult.Invalid(ParseFailureReason.UNTERMINATED_JSON)
            } else {
                ParseResult.Invalid(ParseFailureReason.INVALID_JSON)
            }
        }
        if (!extracted.startsWith("{") || !extracted.endsWith("}")) {
            return if (hasEnvelopeMarker) {
                ParseResult.Invalid(ParseFailureReason.MIXED_PROSE)
            } else {
                ParseResult.NotAToolCall
            }
        }

        return try {
            val root = JSONObject(extracted)
            val keys = root.keys().asSequence().toSet()
            if (keys != setOf("tool_call") && keys != setOf("tool_calls")) {
                return ParseResult.Invalid(ParseFailureReason.UNEXPECTED_ENVELOPE_CONTENT)
            }
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

}
