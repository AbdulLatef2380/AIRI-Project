package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest

/**
 * Anthropic Messages content-block contract.
 *
 * Native binary support is deliberately limited to image blocks and PDF
 * document blocks. Office files continue through AIRI's bounded text
 * extraction path until a provider-native Office route is implemented.
 */
internal object AnthropicPayloadContract {
    fun attachmentIds(request: ExecutionRequest): List<String> =
        (request.imageParts.map { it.attachmentId } + request.inlineDataParts.map { it.attachmentId })
            .filter { it.isNotBlank() }
            .distinct()

    fun containsNonEmptyContent(request: ExecutionRequest): Boolean =
        request.imageParts.all { it.mimeType.isNotBlank() && it.base64Data.isNotBlank() } &&
            request.inlineDataParts.all {
                it.mimeType.equals("application/pdf", ignoreCase = true) && it.base64Data.isNotBlank()
            }

    fun buildRequestBody(request: ExecutionRequest, model: String): String = buildString {
        append("{")
        append("\"model\":${jsonString(model)},")
        append("\"max_tokens\":${request.maxTokens},")
        if (request.systemPrompt.isNotBlank()) {
            append("\"system\":${jsonString(request.systemPrompt)},")
        }
        append("\"messages\":[")
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
            append("{\"type\":\"text\",\"text\":${jsonString(request.prompt)}}")
            hasContent = true
        }
        request.imageParts.forEach { image ->
            if (hasContent) append(",")
            append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":")
            append(jsonString(image.mimeType.ifBlank { "image/jpeg" }))
            append(",\"data\":${jsonString(image.base64Data)}}}")
            hasContent = true
        }
        request.inlineDataParts.forEach { file ->
            if (hasContent) append(",")
            append("{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":")
            append(jsonString(file.mimeType))
            append(",\"data\":${jsonString(file.base64Data)}}}")
            hasContent = true
        }
        append("]}")
        append("],\"stream\":true}")
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")}\""
}
