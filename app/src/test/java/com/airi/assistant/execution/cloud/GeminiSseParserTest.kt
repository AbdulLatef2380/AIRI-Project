package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSseParserTest {
    @Test
    fun terminalReasonWithJsonWhitespaceIsAccepted() {
        val event = GeminiSseParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"Hello"}]}, "finishReason": "STOP"}]}"""
        )

        assertEquals("Hello", event.text)
        assertTrue(event.terminal)
        assertFalse(event.hasError)
    }

    @Test
    fun escapedUnicodeAndUsageAreDecodedStructurally() {
        val event = GeminiSseParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"\u0645\u0631\u062d\u0628\u0627"},{"text":" world"}]},"finishReason":"MAX_TOKENS"}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":4}}"""
        )

        assertEquals("مرحبا world", event.text)
        assertTrue(event.terminal)
        assertEquals(12, event.promptTokens)
        assertEquals(4, event.completionTokens)
    }

    @Test
    fun intermediateChunkIsNotMistakenForTerminal() {
        val event = GeminiSseParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""
        )

        assertEquals("partial", event.text)
        assertFalse(event.terminal)
        assertFalse(event.hasError)
    }

    @Test
    fun providerErrorIsDetectedWithoutExposingItsPayload() {
        val event = GeminiSseParser.parse(
            """{"error":{"code":429,"message":"private provider detail"}}"""
        )

        assertTrue(event.hasError)
        assertEquals("", event.text)
        assertFalse(event.terminal)
    }
}
