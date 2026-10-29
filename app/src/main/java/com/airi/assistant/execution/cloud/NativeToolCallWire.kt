package com.airi.assistant.execution.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Bridges provider-native calls to the existing, validated AgentLoop call envelope. */
internal object NativeToolCallWire {
    data class Call(val name: String, val argumentsJson: String, val id: String = "")

    fun encode(call: Call): String = encode(listOf(call))

    fun encode(calls: List<Call>): String {
        require(calls.isNotEmpty()) { "At least one native tool call is required" }
        if (calls.size == 1) {
            return JSONObject().apply { put("tool_call", callJson(calls.single())) }.toString()
        }
        return JSONObject().apply {
            put("tool_calls", JSONArray().apply { calls.forEach { put(callJson(it)) } })
        }.toString()
    }

    /** Decode a provider-normalized native envelope before text parsing fallback. */
    fun decode(content: String): List<Call> {
        val root = runCatching { JSONObject(content.trim()) }.getOrNull() ?: return emptyList()
        val objects = when {
            root.has("tool_call") -> listOfNotNull(root.optJSONObject("tool_call"))
            root.has("tool_calls") -> buildList {
                val calls = root.optJSONArray("tool_calls") ?: return@buildList
                for (index in 0 until calls.length()) calls.optJSONObject(index)?.let(::add)
            }
            else -> emptyList()
        }
        return objects.mapNotNull { call ->
            val name = call.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val args = call.optJSONObject("args") ?: call.optJSONObject("arguments") ?: JSONObject()
            Call(name = name, argumentsJson = args.toString(), id = call.optString("id"))
        }
    }

    private fun callJson(call: Call): JSONObject = JSONObject().apply {
        put("name", call.name)
        put("args", runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() })
        if (call.id.isNotBlank()) put("id", call.id)
    }
}
