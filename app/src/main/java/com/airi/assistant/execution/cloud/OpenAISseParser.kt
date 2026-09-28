package com.airi.assistant.execution.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Pure decoder for one OpenAI-compatible SSE data payload. */
internal object OpenAISseParser {
    data class Event(
        val text: String = "",
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val hasProviderError: Boolean = false,
        val errorCode: String? = null,
        val errorType: String? = null,
        val malformed: Boolean = false,
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
        val text = delta?.get("content")?.textContent().orEmpty()
        val usage = root.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
        val promptTokens = usage?.intValue("prompt_tokens")
        val completionTokens = usage?.intValue("completion_tokens")

        // A usage-only final chunk legitimately has no choices; reject only a
        // payload that has neither a choices field nor usage nor an error.
        if (choices == null && usage == null) return Event(malformed = true)
        return Event(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
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
