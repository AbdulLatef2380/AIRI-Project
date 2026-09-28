package com.airi.assistant.connector.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** Strict, side-effect-free decoder for OpenAI-compatible chat completion JSON. */
internal object OpenAiContentDecoder {
    fun extractContent(json: String): String {
        val rootElement = runCatching { JsonParser.parseString(json) }
            .getOrElse { throw IOException("openai returned malformed JSON", it) }
        if (!rootElement.isJsonObject) throw IOException("openai returned a non-object response")
        val root = rootElement.asJsonObject

        root.get("error")?.takeUnless { it.isJsonNull }?.let { error ->
            val code = error.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("code")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            throw IOException("openai provider error${code?.let { " ($it)" }.orEmpty()}")
        }

        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IOException("openai response has no choices")
        val first = choices.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("openai response has no usable choice")
        val message = first.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("openai response has no message")
        val content = message.get("content")?.decodeTextContent()
            ?: throw IOException("openai response has no text content")
        if (content.isBlank()) throw IOException("openai returned empty content")
        return content
    }

    private fun JsonElement.decodeTextContent(): String? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonArray -> asJsonArray.joinToString(separator = "") { part ->
            when {
                part.isJsonPrimitive && part.asJsonPrimitive.isString -> part.asString
                part.isJsonObject -> part.asJsonObject.textField()
                else -> ""
            }
        }
        else -> null
    }

    private fun JsonObject.textField(): String =
        get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
}
