package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun append_retainsTypedAuthorizationFailureCode() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)
        buffer.begin("exec-1")

        val event = buffer.append(
            executionId = "exec-1",
            kind = ExecutionTraceKind.TOOL_FAILED,
            summary = "Tool failed: skill_gmail",
            actionId = "tool-1",
            errorCode = "authorization_required",
        )!!

        assertEquals("authorization_required", event.errorCode)
    }

    @Test
    fun append_normalizesAndRejectsUnsafeErrorCodes() {
        val buffer = ExecutionTraceBuffer(maxEntries = 3)
        buffer.begin("exec-1")

        val normalized = buffer.append(
            executionId = "exec-1",
            kind = ExecutionTraceKind.TOOL_FAILED,
            summary = "Tool failed",
            errorCode = " AUTHORIZATION_REQUIRED ",
        )!!
        val unsafe = buffer.append(
            executionId = "exec-1",
            kind = ExecutionTraceKind.TOOL_FAILED,
            summary = "Tool failed",
            errorCode = "authorization_required\nprovider-secret",
        )!!

        assertEquals("authorization_required", normalized.errorCode)
        assertNull(unsafe.errorCode)
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

    @Test
    fun append_preservesTerminalFailureBeforeEvictingActiveNoise() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)
        buffer.begin("exec-terminal")
        buffer.append("exec-terminal", ExecutionTraceKind.PLANNING, "Planning")
        val failure = buffer.append(
            "exec-terminal",
            ExecutionTraceKind.FAILED,
            "Execution failed",
            errorCode = "provider_unavailable",
        )!!
        val active = buffer.append("exec-terminal", ExecutionTraceKind.REFLECTING, "Recovering")!!

        assertEquals(listOf(failure, active), buffer.snapshot())
    }

    @Test
    fun buffer_bindsEventsToExplicitSessionAndRejectsStaleExecution() {
        val buffer = ExecutionTraceBuffer(maxEntries = 2)
        buffer.begin(executionId = "exec-current", sessionId = "session-current")

        val current = buffer.append("exec-current", ExecutionTraceKind.PLANNING, "Planning")!!
        assertEquals("session-current", current.sessionId)

        buffer.begin(executionId = "exec-next", sessionId = "session-next")
        assertNull(buffer.append("exec-current", ExecutionTraceKind.COMPLETED, "Stale completion"))
        val next = buffer.append("exec-next", ExecutionTraceKind.PLANNING, "Planning")!!
        assertEquals("session-next", next.sessionId)
        assertEquals(listOf(next), buffer.snapshot())
    }

    @Test
    fun append_isThreadSafeAndRetainsOnlyTheNewestBoundedWindow() {
        val buffer = ExecutionTraceBuffer(maxEntries = 1_000)
        buffer.begin("exec-concurrent")
        val executor = Executors.newFixedThreadPool(8)

        repeat(5_000) { index ->
            executor.submit {
                buffer.append(
                    executionId = "exec-concurrent",
                    kind = ExecutionTraceKind.TOOL_STARTED,
                    summary = "Read file $index",
                    actionId = "action-$index",
                )
            }
        }
        executor.shutdown()

        assertEquals(true, executor.awaitTermination(10, TimeUnit.SECONDS))
        val retained = buffer.snapshot()
        assertEquals(1_000, retained.size)
        assertEquals(1_000, retained.map { it.sequence }.distinct().size)
        assertEquals(5_000L, retained.maxOf { it.sequence })
    }
}
