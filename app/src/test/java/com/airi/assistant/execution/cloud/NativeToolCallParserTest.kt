package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolCallParserTest {
    @Test
    fun normalizedNativeEnvelopeDecodesBeforeTextFallback() {
        val calls = NativeToolCallWire.decode(
            "{\"tool_calls\":[{\"id\":\"call-1\",\"name\":\"web_search\",\"args\":{\"query\":\"AIRI\"}}]}"
        )
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
        assertEquals("call-1", calls.single().id)
        assertTrue(calls.single().argumentsJson.contains("AIRI"))
    }

    @Test
    fun geminiFunctionCallIsNormalized() {
        val event = GeminiSseParser.parse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"web_search","args":{"query":"أخبار السودان"}}}]},"finishReason":"STOP"}]}"""
        )
        assertEquals("web_search", event.toolCalls.single().name)
        assertTrue(event.toolCalls.single().argumentsJson.contains("query"))
    }

    @Test
    fun openAiFunctionCallDeltaIsNormalized() {
        val event = OpenAISseParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"web_search","arguments":"{\\\"query\\\":\\\"news\\\"}"}}]}}]}"""
        )
        assertEquals("web_search", event.toolCalls.single().name)
        assertEquals("call-1", event.toolCalls.single().id)
    }
}
