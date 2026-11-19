package com.airi.assistant.ai.skills.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAgentSkillTest {
    @Test
    fun truncationUsesMarkerAndPreservesBeginningAndEnd() {
        val source = buildString {
            append("BEGINNING-")
            append("x".repeat(5000))
            append("-END")
        }

        val result = truncateResearchResult(source)

        assertEquals(4000, result.length)
        assertTrue(result.startsWith("BEGINNING-"))
        assertTrue(result.endsWith("-END"))
        assertTrue(result.contains("[TRUNCATED "))
        assertTrue(result.contains("omitted_chars="))
        assertTrue(result.contains("original_chars=${source.length}"))
        assertTrue(result.contains("kept_prefix="))
        assertTrue(result.contains("kept_suffix="))
    }

    @Test
    fun shortResultIsNotMarkedOrChanged() {
        val source = "short research result"

        assertEquals(source, truncateResearchResult(source))
    }
}
