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

    @Test
    fun truncatedArgumentsStayInvalidAfterEncodeAndDecode() {
        val truncated = NativeToolCallWire.Call("write_file", "{\"filename\":\"calc.py\",\"content\":\"import tk", "c1")
        assertTrue(!truncated.argumentsValid)
        val decoded = NativeToolCallWire.decode(NativeToolCallWire.encode(truncated))
        assertEquals(1, decoded.size)
        assertTrue("invalid arguments must not become {}", !decoded.single().argumentsValid)
        assertEquals("c1", decoded.single().id)
    }

    @Test
    fun validArgumentsRoundTrip() {
        val call = NativeToolCallWire.Call("web_search", "{\"query\":\"x\"}", "c2")
        val decoded = NativeToolCallWire.decode(NativeToolCallWire.encode(call)).single()
        assertTrue(decoded.argumentsValid)
        assertTrue(decoded.argumentsJson.contains("\"query\""))
    }
}
