package com.airi.assistant.ai.intent

import org.json.JSONObject

data class ToolCall(
    val toolName: String,
    val params: Map<String, String>
)

object ToolCallParser {

    fun parse(modelOutput: String): ToolCall? {
        val trimmed = modelOutput.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            val json = JSONObject(trimmed)
            val toolName = json.optString("tool", "").takeIf { it.isNotBlank() } ?: return null
            val paramsJson = json.optJSONObject("params") ?: JSONObject()
            val params = mutableMapOf<String, String>()
            for (key in paramsJson.keys()) {
                params[key] = paramsJson.optString(key, "")
            }
            ToolCall(toolName, params)
        } catch (e: Exception) {
            null
        }
    }
}
