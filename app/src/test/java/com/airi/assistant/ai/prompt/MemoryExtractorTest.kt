package com.airi.assistant.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {
    @Test
    fun extractsEnglishPreferenceAsCanonicalFact() {
        assertEquals(
            listOf("preference=concise answers"),
            MemoryExtractor.extract("Remember this: I prefer concise answers."),
        )
    }

    @Test
    fun extractsArabicLocationAsCanonicalFact() {
        assertEquals(
            listOf("location=الخرطوم"),
            MemoryExtractor.extract("احفظ هذا: أعيش في الخرطوم"),
        )
    }

    @Test
    fun blankAndOversizedMessagesProduceNoFacts() {
        assertTrue(MemoryExtractor.extract("  \n ").isEmpty())
        assertTrue(MemoryExtractor.extract("I prefer " + "x".repeat(4_000)).isEmpty())
    }
}
