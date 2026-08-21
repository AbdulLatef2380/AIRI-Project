package com.airi.core.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextContextPolicyTest {

    private val budget = TextContextBudget(
        modelContextTokens = 1_000,
        reservedOutputTokens = 200,
        systemTokens = 100,
        conversationTokens = 150,
        memoryTokens = 50
    )

    @Test
    fun `classifies attachment from measured remaining budget`() {
        assertEquals(500, budget.availableAttachmentTokens())
        assertEquals(TextAttachmentHandling.DIRECT_CONTEXT, TextContextPolicy.handling(200, budget))
        assertEquals(TextAttachmentHandling.STRUCTURED_CHUNKS, TextContextPolicy.handling(700, budget))
        assertEquals(TextAttachmentHandling.RETRIEVAL_REQUIRED, TextContextPolicy.handling(1_100, budget))
        assertEquals(
            TextAttachmentHandling.REJECTED_NO_BUDGET,
            TextContextPolicy.handling(1, budget.copy(existingAttachmentTokens = 500))
        )
    }

    @Test
    fun `chunker preserves readable boundaries and source offsets`() {
        val text = "عنوان\n\nمرحبا AIRI 123 https://example.com\n\n```kotlin\nfun main() = println(\"ok\")\n```"
        val chunks = StructuredTextChunker.split("file-1", text, "text/markdown", maxChunkChars = 38)

        assertTrue(chunks.size >= 2)
        assertEquals("file-1-0", chunks.first().chunkId)
        assertTrue(chunks.all { it.text == text.substring(it.startOffset, it.endOffsetExclusive) })
        assertTrue(chunks.joinToString(" ") { it.text }.contains("https://example.com"))
    }
}
