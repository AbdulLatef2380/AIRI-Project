package com.airi.core.attachments

data class TextContextBudget(
    val modelContextTokens: Int,
    val reservedOutputTokens: Int,
    val systemTokens: Int,
    val conversationTokens: Int,
    val memoryTokens: Int,
    val existingAttachmentTokens: Int = 0
) {
    fun availableAttachmentTokens(): Int = (
        modelContextTokens - reservedOutputTokens - systemTokens - conversationTokens - memoryTokens - existingAttachmentTokens
    ).coerceAtLeast(0)
}

enum class TextAttachmentHandling {
    DIRECT_CONTEXT,
    STRUCTURED_CHUNKS,
    RETRIEVAL_REQUIRED,
    REJECTED_NO_BUDGET
}

object TextContextPolicy {
    fun handling(
        attachmentTokens: Int,
        budget: TextContextBudget,
        directContextRatio: Double = 0.5,
        chunkContextRatio: Double = 2.0
    ): TextAttachmentHandling {
        require(attachmentTokens >= 0) { "Attachment token estimate cannot be negative." }
        require(directContextRatio in 0.0..1.0) { "Direct context ratio must be between zero and one." }
        require(chunkContextRatio >= 1.0) { "Chunk context ratio must allow at least the available budget." }

        val available = budget.availableAttachmentTokens()
        if (available == 0) return TextAttachmentHandling.REJECTED_NO_BUDGET
        return when {
            attachmentTokens <= (available * directContextRatio).toInt() -> TextAttachmentHandling.DIRECT_CONTEXT
            attachmentTokens <= (available * chunkContextRatio).toInt() -> TextAttachmentHandling.STRUCTURED_CHUNKS
            else -> TextAttachmentHandling.RETRIEVAL_REQUIRED
        }
    }
}

data class TextAttachmentChunk(
    val attachmentId: String,
    val chunkId: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val section: String?,
    val mimeType: String,
    val text: String
) {
    init {
        require(attachmentId.isNotBlank())
        require(chunkId.isNotBlank())
        require(startOffset >= 0)
        require(endOffsetExclusive >= startOffset)
        require(text.isNotBlank())
    }
}
