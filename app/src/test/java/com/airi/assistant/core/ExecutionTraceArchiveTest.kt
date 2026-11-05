package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExecutionTraceArchiveTest {

    @Test
    fun eventJson_roundTripsEveryField() {
        val event = ExecutionTraceEvent(
            executionId = "exec-1",
            sequence = 3,
            timestampMs = 99L,
            kind = ExecutionTraceKind.TOOL_FAILED,
            summary = "failed",
            detail = "code=io",
            actionId = "a1",
            durationMs = 12L,
            errorCode = "io",
            sessionId = "s1",
            machineTag = "tool_call",
            parentId = "p1",
        )
        assertEquals(event, ExecutionTraceArchive.eventFromJson(ExecutionTraceArchive.eventToJson(event)))
    }

    @Test
    fun eventJson_keepsNullOptionalFieldsNull() {
        val event = ExecutionTraceEvent("e", 1, 1L, ExecutionTraceKind.PLANNING, "plan")
        assertEquals(event, ExecutionTraceArchive.eventFromJson(ExecutionTraceArchive.eventToJson(event)))
    }

    @Test
    fun archive_survivesReattachmentAndLoadsByMessageId() {
        val file = File.createTempFile("execution_trace_archive", ".json").apply { deleteOnExit() }
        val event = ExecutionTraceEvent(
            executionId = "exec-persisted",
            sequence = 1,
            timestampMs = 123L,
            kind = ExecutionTraceKind.TOOL_COMPLETED,
            summary = "saved",
            sessionId = "session-1",
        )

        ExecutionTraceArchive.attach(file)
        ExecutionTraceArchive.put(42L, listOf(event))

        val deadline = System.currentTimeMillis() + 5_000L
        while (file.length() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertTrue("archive writer did not create the persisted snapshot", file.length() > 0L)

        ExecutionTraceArchive.attach(file)
        assertEquals(mapOf(42L to listOf(event)), ExecutionTraceArchive.getAll(listOf(42L)))
        assertEquals(emptyMap<Long, List<ExecutionTraceEvent>>(), ExecutionTraceArchive.getAll(listOf(99L)))
    }

    @Test
    fun corruptArchiveDoesNotPublishPartiallyParsedEntries() {
        val file = File.createTempFile("execution_trace_archive_corrupt", ".json").apply {
            writeText("[{\"messageId\":1,\"events\":[]},{\"messageId\":2")
            deleteOnExit()
        }

        ExecutionTraceArchive.attach(file)

        assertEquals(emptyMap<Long, List<ExecutionTraceEvent>>(), ExecutionTraceArchive.getAll(listOf(1L, 2L)))
    }
}
