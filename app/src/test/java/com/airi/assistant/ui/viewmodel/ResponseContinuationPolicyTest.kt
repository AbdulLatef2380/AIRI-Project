package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseContinuationPolicyTest {
    @Test fun onlyIncompleteNonEmptyResponsesCanContinue() {
        assertTrue(ResponseContinuationPolicy.canContinue(GenerationResponseStatus.INCOMPLETE, "partial"))
        assertFalse(ResponseContinuationPolicy.canContinue(GenerationResponseStatus.SUCCESS, "partial"))
        assertFalse(ResponseContinuationPolicy.canContinue(GenerationResponseStatus.INCOMPLETE, "  "))
    }

    @Test fun promptIsBoundedAndExplicitlyAvoidsRepetition() {
        val prompt = ResponseContinuationPolicy.buildPrompt("x".repeat(20_000), "LENGTH_LIMIT")
        assertNotNull(prompt)
        assertTrue(prompt!!.length <= ResponseContinuationPolicy.MAX_PROMPT_CHARS)
        assertTrue(prompt.contains("Do not repeat"))
    }

    @Test fun mergeRemovesProviderOverlap() {
        assertEquals("hello world", ResponseContinuationPolicy.merge("hello ", " world"))
    }
}
