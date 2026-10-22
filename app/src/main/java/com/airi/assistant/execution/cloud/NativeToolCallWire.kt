package com.airi.assistant.execution.cloud

import org.json.JSONObject

/** Bridges provider-native calls to the existing, validated AgentLoop call envelope. */
internal object NativeToolCallWire {
    data class Call(val name: String, val argumentsJson: String, val id: String = "")

    fun encode(call: Call): String {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
        return JSONObject().apply {
            put("tool_call", JSONObject().apply {
                put("name", call.name)
                put("args", args)
                if (call.id.isNotBlank()) put("id", call.id)
            })
        }.toString()
    }
}
