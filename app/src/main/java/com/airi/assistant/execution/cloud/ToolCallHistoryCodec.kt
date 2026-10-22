package com.airi.assistant.execution.cloud

import org.json.JSONObject

internal object ToolCallHistoryCodec {
    data class Call(val name: String, val arguments: JSONObject, val id: String = "")

    fun parseMarker(content: String): Call? {
        val root = runCatching { JSONObject(content.trim()) }.getOrNull() ?: return null
        val call = root.optJSONObject("tool_call") ?: return null
        val name = call.optString("name", "").trim()
        if (name.isBlank()) return null
        return Call(
            name = name,
            arguments = call.optJSONObject("args") ?: JSONObject(),
            id = call.optString("id", ""),
        )
    }
}
