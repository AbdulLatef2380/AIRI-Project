package com.airi.assistant.memory.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagQueryPolicyTest {

    @Test
    fun blankQueriesAreRejected() {
        assertFalse(RagQueryPolicy.accepts("  \n"))
        assertTrue(RagQueryPolicy.accepts("project status"))
    }

    @Test
    fun limitsAreBoundedBeforeRetrieval() {
        assertEquals(1, RagQueryPolicy.normalizeLimit(0))
        assertEquals(5, RagQueryPolicy.normalizeLimit(99))
        assertEquals(3, RagQueryPolicy.normalizeLimit(3))
    }

    @Test
    fun queryWhitespaceIsRemoved() {
        assertEquals("project status", RagQueryPolicy.normalizeQuery("  project status  "))
    }
}
