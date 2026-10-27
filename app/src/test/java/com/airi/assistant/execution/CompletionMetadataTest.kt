package com.airi.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionMetadataTest {
    @Test
    fun providerLengthReasonsNormalizeToOneContinuationReason() {
        listOf("length", "MAX_TOKENS", "max_output_tokens", "incomplete", "pause_turn", "CONTINUATION")
            .forEach { raw ->
                assertEquals(CompletionReason.LENGTH_LIMIT, CompletionMetadata.fromProvider("test", raw).reason)
            }
    }

    @Test
    fun naturalStopsDoNotTriggerContinuation() {
        assertEquals(CompletionReason.STOP, CompletionMetadata.fromProvider("test", "stop").reason)
        assertEquals(CompletionReason.TOOL_CALLS, CompletionMetadata.fromProvider("test", "tool_calls").reason)
    }
}
