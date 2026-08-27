package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutionTraceBufferTest {

    @Test
    fun begin_requiresAnExecutionOwner() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)

        assertThrows(IllegalArgumentException::class.java) { buffer.begin("") }
    }

    @Test
    fun append_rejectsIncompleteEventsAndSequencesAcceptedEvents() {
        val buffer = ExecutionTraceBuffer(maxEntries = 3)
        buffer.begin("exec-1")

        assertNull(buffer.append("", ExecutionTraceKind.PLANNING, "Planning"))
        assertNull(buffer.append("exec-1", ExecutionTraceKind.PLANNING, ""))
        val first = buffer.append("exec-1", ExecutionTraceKind.PLANNING, "Planning")!!
        val second = buffer.append("exec-1", ExecutionTraceKind.STEP_STARTED, "Step")!!

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(listOf(first, second), buffer.snapshot())
    }

    @Test
    fun append_retainsActionIdentityAndNormalizesNegativeDuration() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)
        buffer.begin("exec-1")

        val event = buffer.append(
            executionId = "exec-1",
            kind = ExecutionTraceKind.TOOL_COMPLETED,
            summary = "Tool completed",
            actionId = "tool-1",
            durationMs = -4L,
        )!!

        assertEquals("tool-1", event.actionId)
        assertEquals(0L, event.durationMs)
    }

    @Test
    fun append_evictsOldestEntriesAtBoundedCapacity() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)
        buffer.begin("exec-1")
        buffer.append("exec-1", ExecutionTraceKind.PLANNING, "Planning")
        val retainedFirst = buffer.append("exec-1", ExecutionTraceKind.STEP_STARTED, "Step")!!
        val retainedLast = buffer.append("exec-1", ExecutionTraceKind.COMPLETED, "Completed")!!

        assertEquals(listOf(retainedFirst, retainedLast), buffer.snapshot())
        assertEquals(2L, retainedFirst.sequence)
        assertEquals(3L, retainedLast.sequence)
    }
}
