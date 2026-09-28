package com.airi.assistant.connector.api

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OpenAiContentDecoderTest {
    @Test
    fun decodesStandardChatCompletion() {
        assertEquals(
            "Hello",
            OpenAiContentDecoder.extractContent(
                """{"choices":[{"message":{"content":"Hello"}}]}"""
            )
        )
    }

    @Test
    fun concatenatesTextPartsFromStructuredContent() {
        assertEquals(
            "AB",
            OpenAiContentDecoder.extractContent(
                """{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}"""
            )
        )
    }

    @Test
    fun rejectsEmptyAndMalformedResponsesInsteadOfReportingSuccess() {
        listOf(
            """{"choices":[{"message":{"content":"  "}}]}""",
            "not json",
            """{"choices":[]}""",
            """{"choices":[{"message":{"content":null}}]}""",
        ).forEach { payload ->
            try {
                OpenAiContentDecoder.extractContent(payload)
                fail("Expected malformed/empty response to throw")
            } catch (_: java.io.IOException) {
                // Expected: the caller maps this to a failed connector result.
            }
        }
    }

    @Test
    fun providerErrorEnvelopeIsNotMisreportedAsMissingChoicesSuccess() {
        try {
            OpenAiContentDecoder.extractContent(
                """{"error":{"message":"private detail","code":"rate_limit_exceeded"}}"""
            )
            fail("Expected provider error")
        } catch (error: java.io.IOException) {
            assertEquals("openai provider error (rate_limit_exceeded)", error.message)
        }
    }
}
