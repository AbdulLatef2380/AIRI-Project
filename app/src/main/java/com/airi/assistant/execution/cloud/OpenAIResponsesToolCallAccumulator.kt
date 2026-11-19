package com.airi.assistant.execution.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Collects OpenAI Responses API function-call items across SSE event boundaries. */
internal class OpenAIResponsesToolCallAccumulator {
    private val callsByItemId = linkedMapOf<String, NativeToolCallWire.Call>()

    fun accept(payload: String) {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (root.optString("type")) {
            "response.output_item.added", "response.output_item.done" ->
                root.optJSONObject("item")?.let(::acceptItem)
            "response.function_call_arguments.delta" -> {
                val itemId = root.optString("item_id")
                if (itemId.isBlank()) return
                val current = callsByItemId[itemId] ?: NativeToolCallWire.Call("", "", itemId)
                callsByItemId[itemId] = current.copy(
                    argumentsJson = current.argumentsJson + root.optString("delta"),
                )
            }
            "response.function_call_arguments.done" -> {
                val itemId = root.optString("item_id")
                if (itemId.isBlank()) return
                val current = callsByItemId[itemId] ?: NativeToolCallWire.Call("", "", itemId)
                callsByItemId[itemId] = current.copy(argumentsJson = root.optString("arguments"))
            }
            "response.completed", "response.done" -> {
                root.optJSONObject("response")?.optJSONArray("output")?.let(::acceptOutput)
            }
        }
    }

    fun calls(): List<NativeToolCallWire.Call> = callsByItemId.values
        .filter { it.name.isNotBlank() }
        .toList()

    private fun acceptOutput(output: JSONArray) {
        for (index in 0 until output.length()) {
            output.optJSONObject(index)?.let(::acceptItem)
        }
    }

    private fun acceptItem(item: JSONObject) {
        if (item.optString("type") != "function_call") return
        val itemId = item.optString("id").ifBlank { item.optString("call_id") }
        val callId = item.optString("call_id").ifBlank { itemId }
        val name = item.optString("name")
        if (itemId.isBlank() || name.isBlank()) return
        callsByItemId[itemId] = NativeToolCallWire.Call(
            name = name,
            argumentsJson = item.optString("arguments", callsByItemId[itemId]?.argumentsJson.orEmpty()),
            id = callId,
        )
    }
}
