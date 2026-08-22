package com.airi.assistant.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionPolicyTest {

    @Test
    fun compressesHistoryWhenExceedingThreshold() {
        val longHistory = (1..30).map { Pair("user", "Message number $it with substantial content padding to simulate long RAG context.") }
        val compressed = ContextCompressionPolicy.compressMessages(longHistory, maxRecent = 5)
        assertEquals(6, compressed.size)
        assertTrue(compressed[0].second.contains("archived"))
    }
}
