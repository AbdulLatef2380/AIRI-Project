package com.airi.core.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkSelectorTest {

    private fun chunk(id: String, text: String) = TextAttachmentChunk(
        attachmentId = "attachment-1",
        chunkId = id,
        startOffset = 0,
        endOffsetExclusive = text.length,
        section = null,
        mimeType = "text/plain",
        text = text
    )

    @Test
    fun `selects relevant chunks within the measured budget`() {
        val selected = TextChunkSelector.select(
            query = "كيف يعمل token budget",
            chunks = listOf(
                chunk("a", "هذا نص عام."),
                chunk("b", "يحافظ token budget على مساحة للإجابة."),
                chunk("c", "يستخدم الاختيار قطعاً مرتبة."),
                chunk("d", "يحافظ token budget على مساحة للإجابة.")
            ),
            tokenBudget = 5,
            estimateTokens = { text -> if (text.contains("token")) 3 else 2 }
        )

        assertEquals(listOf("b", "a"), selected.map { it.chunk.chunkId })
        assertTrue(selected.sumOf { it.estimatedTokens } <= 5)
    }

    @Test
    fun `returns no chunks for no budget`() {
        assertTrue(
            TextChunkSelector.select("query", listOf(chunk("a", "text")), 0) { 1 }.isEmpty()
        )
    }
}
