package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIResponsesToolCallAccumulatorTest {
    @Test
    fun assemblesFunctionCallFromAddedItemAndArgumentDeltas() {
        val accumulator = OpenAIResponsesToolCallAccumulator()
        accumulator.accept("""{"type":"response.output_item.added","item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"skill_web_search_search","arguments":""}}""")
        accumulator.accept("""{"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{\"query\":"}""")
        accumulator.accept("""{"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"\"weather\"}"}""")

        val calls = accumulator.calls()
        assertEquals(1, calls.size)
        assertEquals("skill_web_search_search", calls.single().name)
        assertEquals("call_1", calls.single().id)
        assertEquals("{\"query\":\"weather\"}", calls.single().argumentsJson)
        assertTrue(calls.single().argumentsValid)
    }

    @Test
    fun completionOutputIsAuthoritativeAndSupportsMultipleCalls() {
        val accumulator = OpenAIResponsesToolCallAccumulator()
        accumulator.accept("""{"type":"response.completed","response":{"output":[{"id":"item_a","type":"function_call","call_id":"call_a","name":"skill_search","arguments":"{\"query\":\"a\"}"},{"id":"item_b","type":"function_call","call_id":"call_b","name":"skill_notes","arguments":"{\"text\":\"b\"}"}]}}""")

        val calls = accumulator.calls()
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("skill_search", "skill_notes"), calls.map { it.name })
        assertTrue(calls.all { it.argumentsValid })
    }
}
