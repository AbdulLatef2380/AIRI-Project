package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest

/**
 * Provider-specific wire contract for OpenAI's Responses API.
 *
 * This is intentionally separate from the OpenAI-compatible Chat Completions
 * builder: OpenRouter, Kimi, and custom endpoints must not receive Responses
 * wire fields unless they explicitly implement that protocol.
 */
internal object OpenAIResponsesPayloadContract {
    fun requiresResponses(request: ExecutionRequest): Boolean =
        request.imageParts.isNotEmpty() || request.inlineDataParts.isNotEmpty()

    fun attachmentIds(request: ExecutionRequest): List<String> =
        (request.imageParts.map { it.attachmentId } + request.inlineDataParts.map { it.attachmentId })
            .filter { it.isNotBlank() }
            .distinct()

    fun containsNonEmptyContent(request: ExecutionRequest): Boolean =
        request.imageParts.all { it.mimeType.isNotBlank() && it.base64Data.isNotBlank() } &&
            request.inlineDataParts.all { it.mimeType.isNotBlank() && it.base64Data.isNotBlank() }

    fun buildRequestBody(request: ExecutionRequest, model: String): String = buildString {
        append("{")
        append("\"model\":${jsonString(model)},")
        if (request.systemPrompt.isNotBlank()) {
            append("\"instructions\":${jsonString(request.systemPrompt)},")
        }
        append("\"input\":[")
        var needsComma = false
        request.conversationHistory.forEach { turn ->
            if (needsComma) append(",")
            append("{\"role\":${jsonString(turn.role)},\"content\":${jsonString(turn.content)}}")
            needsComma = true
        }
        if (needsComma) append(",")
        append("{\"role\":\"user\",\"content\":[")
        var hasContent = false
        if (request.prompt.isNotBlank()) {
            append("{\"type\":\"input_text\",\"text\":${jsonString(request.prompt)}}")
            hasContent = true
        }
        request.imageParts.forEach { image ->
            if (hasContent) append(",")
            append("{\"type\":\"input_image\",\"image_url\":")
            append(jsonString("data:${image.mimeType.ifBlank { "image/jpeg" }};base64,${image.base64Data}"))
            append("}")
            hasContent = true
        }
        request.inlineDataParts.forEach { file ->
            if (hasContent) append(",")
            append("{\"type\":\"input_file\",\"filename\":${jsonString(file.fileName.ifBlank { "attachment" })},")
            append("\"file_data\":${jsonString("data:${file.mimeType};base64,${file.base64Data}")}}")
            hasContent = true
        }
        append("]}")
        append("],")
        append("\"max_output_tokens\":${request.maxTokens},")
        append("\"temperature\":${request.temperature},")
        append("\"stream\":true")
        append("}")
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")}\""
}
