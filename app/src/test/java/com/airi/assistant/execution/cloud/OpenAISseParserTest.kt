package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAISseParserTest {
    @Test
    fun contentContainingTheWordErrorIsStillOrdinaryText() {
        val event = OpenAISseParser.parse(
            """{"choices":[{"delta":{"content":"the key is \"error\""}}]}"""
        )
        assertEquals("the key is \"error\"", event.text)
        assertFalse(event.hasProviderError)
        assertFalse(event.malformed)
    }

    @Test
    fun providerErrorFieldsAreParsedStructurally() {
        val event = OpenAISseParser.parse(
            """{"error":{"message":"Rate limit reached","type":"rate_limit_error","code":"rate_limit_exceeded"}}"""
        )
        assertTrue(event.hasProviderError)
        assertEquals("rate_limit_exceeded", event.errorCode)
        assertEquals("rate_limit_error", event.errorType)
    }

    @Test
    fun usageOnlyFinalChunkIsValid() {
        val event = OpenAISseParser.parse(
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4}}"""
        )
        assertFalse(event.malformed)
        assertEquals(12, event.promptTokens)
        assertEquals(4, event.completionTokens)
    }

    @Test
    fun malformedPayloadFailsClosed() {
        assertTrue(OpenAISseParser.parse("{not json").malformed)
        assertTrue(OpenAISseParser.parse("[]").malformed)
    }
}
