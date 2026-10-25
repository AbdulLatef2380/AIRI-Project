package com.airi.assistant.execution.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Pure decoder for one OpenAI-compatible SSE data payload. */
internal object OpenAISseParser {
    data class Event(
        val text: String = "",
        val toolCalls: List<ToolCallDelta> = emptyList(),
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val finishReason: String? = null,
        val hasProviderError: Boolean = false,
        val errorCode: String? = null,
        val errorType: String? = null,
        val malformed: Boolean = false,
    )

    data class ToolCallDelta(
        val index: Int,
        val id: String = "",
        val name: String = "",
        val arguments: String = "",
    )

    fun parse(payload: String): Event {
        val root = runCatching { JsonParser.parseString(payload) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return Event(malformed = true)

        val errorElement = root.get("error")
        if (errorElement != null && !errorElement.isJsonNull) {
            if (!errorElement.isJsonObject) return Event(malformed = true)
            val error = errorElement.asJsonObject
            return Event(
                hasProviderError = true,
                errorCode = error.stringValue("code"),
                errorType = error.stringValue("type"),
            )
        }

        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstChoice = choices?.firstObjectOrNull()
        val delta = firstChoice?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
        val finishReason = firstChoice?.stringValue("finish_reason")
        val text = delta?.get("content")?.textContent().orEmpty()
        val toolCalls = delta?.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapIndexedNotNull { index, element ->
                val call = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
                val function = call.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
                ToolCallDelta(
                    index = call.intValue("index") ?: index,
                    id = call.stringValue("id").orEmpty(),
                    name = function?.stringValue("name").orEmpty(),
                    arguments = function?.stringValue("arguments").orEmpty(),
                )
            }.orEmpty()
        val usage = root.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
        val promptTokens = usage?.intValue("prompt_tokens")
        val completionTokens = usage?.intValue("completion_tokens")

        // A usage-only final chunk legitimately has no choices; reject only a
        // payload that has neither a choices field nor usage nor an error.
        if (choices == null && usage == null) return Event(malformed = true)
        return Event(
            text = text,
            toolCalls = toolCalls,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            finishReason = finishReason,
        )
    }

    private fun JsonArray.firstObjectOrNull(): JsonObject? =
        firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringValue(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.intValue(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonElement.textContent(): String = when {
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonArray -> asJsonArray.joinToString(separator = "") { part ->
            when {
                part.isJsonPrimitive && part.asJsonPrimitive.isString -> part.asString
                part.isJsonObject -> part.asJsonObject.stringValue("text").orEmpty()
                else -> ""
            }
        }
        else -> ""
    }
}
