package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest

/** Wire-contract checks shared by the Gemini adapter and payload tests. */
internal object GeminiPayloadContract {
    data class ContentDescriptor(val mimeType: String, val encodedLength: Int)

    fun descriptors(request: ExecutionRequest): List<ContentDescriptor> =
        request.imageParts.map { ContentDescriptor(it.mimeType, it.base64Data.length) } +
            request.inlineDataParts.map { ContentDescriptor(it.mimeType, it.base64Data.length) }

    fun containsNonEmptyInlineContent(request: ExecutionRequest): Boolean =
        descriptors(request).all { it.mimeType.isNotBlank() && it.encodedLength > 0 }
}
