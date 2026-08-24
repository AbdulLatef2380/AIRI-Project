package com.airi.assistant.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectKnowledgeTextPolicyTest {

    @Test
    fun blankTextDoesNotCreatePhantomChunksButShortExplicitTextIsRetained() {
        assertTrue(ProjectKnowledgeTextPolicy.chunkText("   ").isEmpty())
        assertEquals(listOf("short note"), ProjectKnowledgeTextPolicy.chunkText("short note"))
    }

    @Test
    fun longTextIsBoundedAndAdjacentChunksOverlap() {
        val text = buildString {
            repeat(110) { index ->
                append("Paragraph $index contains durable project evidence and retrieval terms for verification. ")
            }
        }
        val chunks = ProjectKnowledgeTextPolicy.chunkText(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.size <= 96)
        assertTrue(chunks.all { it.length >= 80 })
        assertFalse(chunks[0] == chunks[1])
        assertTrue(chunks[0].takeLast(60).any { tailChar -> chunks[1].contains(tailChar) })
    }

    @Test
    fun singleBoundedSegmentStaysIntact() {
        val text = "Evidence ".repeat(40)
        val chunks = ProjectKnowledgeTextPolicy.chunkText(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().contains("Evidence"))
    }
}
