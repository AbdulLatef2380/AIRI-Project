package com.airi.assistant.memory.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRetrievalRankerTest {

    @Test
    fun `ranks authorized passages by score across retrieval sources`() {
        val ranked = RagRetrievalRanker.rank(
            passages = listOf(
                passage(citationId = "memory-1", role = "memory", content = "Persistent preference", score = 0.80f),
                passage(citationId = "message-2", role = "assistant", content = "Direct semantic match", score = 0.94f),
                passage(citationId = "knowledge-3", role = "knowledge", content = "Project reference", score = 0.87f)
            ),
            limit = 3
        )

        assertEquals(listOf("message-2", "knowledge-3", "memory-1"), ranked.map(RetrievedPassage::citationId))
    }

    @Test
    fun `keeps query retrieved passages ahead of durable confidence fallback`() {
        val ranked = RagRetrievalRanker.rank(
            passages = listOf(
                passage(citationId = "memory-1", role = "memory", content = "Durable preference", score = 1f),
                passage(citationId = "message-2", role = "assistant", content = "Direct semantic match", score = 0.41f),
                passage(citationId = "memory-3", role = "memory", content = "Second durable preference", score = 0.01f)
            ),
            limit = 3
        )

        assertEquals(listOf("message-2", "memory-1", "memory-3"), ranked.map(RetrievedPassage::citationId))
    }

    @Test
    fun `keeps highest ranked normalized duplicate regardless of source`() {
        val ranked = RagRetrievalRanker.rank(
            passages = listOf(
                passage(citationId = "memory-1", role = "memory", content = "Deploy   to production!", score = 0.72f),
                passage(citationId = "knowledge-2", role = "knowledge", content = "deploy to PRODUCTION", score = 0.93f),
                passage(citationId = "message-3", role = "assistant", content = "Keep this separate", score = 0.80f)
            ),
            limit = 3
        )

        assertEquals(listOf("knowledge-2", "message-3"), ranked.map(RetrievedPassage::citationId))
    }

    @Test
    fun `returns no passages for non positive limit`() {
        val ranked = RagRetrievalRanker.rank(listOf(passage("memory-1", "memory", "Fact", 1f)), limit = 0)

        assertTrue(ranked.isEmpty())
    }

    private fun passage(
        citationId: String,
        role: String,
        content: String,
        score: Float
    ) = RetrievedPassage(
        citationId = citationId,
        role = role,
        content = content,
        score = score,
        source = "test"
    )
}
