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
            inputParams = mapOf("query" to "safe value"),
        )
        assertEquals(event, ExecutionTraceArchive.eventFromJson(ExecutionTraceArchive.eventToJson(event)))
    }

    @Test
    fun eventJson_keepsNullOptionalFieldsNull() {
        val event = ExecutionTraceEvent("e", 1, 1L, ExecutionTraceKind.PLANNING, summary = "plan")
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

    @Test
    fun corruptPrimary_recoversValidatedBackupWithoutPublishingPartialData() {
        val file = File.createTempFile("execution_trace_archive_backup", ".json").apply { deleteOnExit() }
        val backup = File(file.parentFile, file.name + ".bak").apply { deleteOnExit() }
        val event = ExecutionTraceEvent("exec-backup", 1, 10L, ExecutionTraceKind.COMPLETED, summary = "backup")
        backup.writeText("[{\"messageId\":7,\"events\":[${ExecutionTraceArchive.eventToJson(event)}] }]")
        file.writeText("[{\"messageId\":7")

        ExecutionTraceArchive.attach(file)

        assertEquals(mapOf(7L to listOf(event)), ExecutionTraceArchive.getAll(listOf(7L)))
        assertTrue("recovered primary archive should be valid", file.readText().startsWith("["))
    }

    @Test
    fun validArchive_isPreferredOverStaleTemporaryFile() {
        val file = File.createTempFile("execution_trace_archive_temp", ".json").apply { deleteOnExit() }
        val temp = File(file.parentFile, file.name + ".tmp").apply { deleteOnExit() }
        val primary = ExecutionTraceEvent("exec-primary", 1, 1L, ExecutionTraceKind.COMPLETED, summary = "primary")
        val stale = ExecutionTraceEvent("exec-stale", 1, 2L, ExecutionTraceKind.COMPLETED, summary = "stale")
        file.writeText("[{\"messageId\":1,\"events\":[${ExecutionTraceArchive.eventToJson(primary)}]}]")
        temp.writeText("[{\"messageId\":2,\"events\":[${ExecutionTraceArchive.eventToJson(stale)}]}]")

        ExecutionTraceArchive.attach(file)

        assertEquals(mapOf(1L to listOf(primary)), ExecutionTraceArchive.getAll(listOf(1L, 2L)))
    }

    @Test
    fun deleteMessageAndSession_removeOnlyOwnedTraces() {
        val file = File.createTempFile("execution_trace_archive_delete", ".json").apply { deleteOnExit() }
        val first = ExecutionTraceEvent("exec-1", 1, 1L, ExecutionTraceKind.COMPLETED, summary = "one", sessionId = "session-a")
        val second = ExecutionTraceEvent("exec-2", 1, 2L, ExecutionTraceKind.COMPLETED, summary = "two", sessionId = "session-b")
        val third = ExecutionTraceEvent("exec-3", 1, 3L, ExecutionTraceKind.COMPLETED, summary = "three", sessionId = "session-a")

        ExecutionTraceArchive.attach(file)
        ExecutionTraceArchive.put(1L, listOf(first))
        ExecutionTraceArchive.put(2L, listOf(second))
        ExecutionTraceArchive.put(3L, listOf(third))
        ExecutionTraceArchive.deleteMessage(2L)
        ExecutionTraceArchive.deleteSession("session-a")

        assertEquals(emptyMap<Long, List<ExecutionTraceEvent>>(), ExecutionTraceArchive.getAll(listOf(1L, 2L, 3L)))
    }

    @Test
    fun maxMessagesEvictsOldestMessageWithoutMixingSessions() {
        val file = File.createTempFile("execution_trace_archive_limit", ".json").apply { deleteOnExit() }
        val previousLimit = ExecutionTraceArchive.maxMessages
        try {
            ExecutionTraceArchive.maxMessages = 2
            ExecutionTraceArchive.attach(file)
            ExecutionTraceArchive.put(1L, listOf(ExecutionTraceEvent("e1", 1, 1L, ExecutionTraceKind.COMPLETED, summary = "one", sessionId = "a")))
            ExecutionTraceArchive.put(2L, listOf(ExecutionTraceEvent("e2", 1, 2L, ExecutionTraceKind.COMPLETED, summary = "two", sessionId = "b")))
            ExecutionTraceArchive.put(3L, listOf(ExecutionTraceEvent("e3", 1, 3L, ExecutionTraceKind.COMPLETED, summary = "three", sessionId = "c")))

            val retained = ExecutionTraceArchive.getAll(listOf(1L, 2L, 3L))
            assertEquals(setOf(2L, 3L), retained.keys)
            assertEquals("b", retained.getValue(2L).single().sessionId)
            assertEquals("c", retained.getValue(3L).single().sessionId)
        } finally {
            ExecutionTraceArchive.maxMessages = previousLimit
        }
    }

    @Test
    fun perSessionLimitDropsOldestOfThatSessionOnly() {
        val file = File.createTempFile("execution_trace_archive", ".json").apply { deleteOnExit() }
        val previousSession = ExecutionTraceArchive.maxMessagesPerSession
        val previousGlobal = ExecutionTraceArchive.maxMessages
        try {
            ExecutionTraceArchive.maxMessages = 50
            ExecutionTraceArchive.maxMessagesPerSession = 2
            ExecutionTraceArchive.attach(file)
            fun trace(id: Long, session: String) =
                listOf(ExecutionTraceEvent("e$id", 1, id, ExecutionTraceKind.COMPLETED, summary = "event-$id", sessionId = session))
            ExecutionTraceArchive.put(1L, trace(1L, "busy"))
            ExecutionTraceArchive.put(2L, trace(2L, "quiet"))
            ExecutionTraceArchive.put(3L, trace(3L, "busy"))
            ExecutionTraceArchive.put(4L, trace(4L, "busy"))

            val retained = ExecutionTraceArchive.getAll(listOf(1L, 2L, 3L, 4L))
            assertEquals(setOf(2L, 3L, 4L), retained.keys)
        } finally {
            ExecutionTraceArchive.maxMessagesPerSession = previousSession
            ExecutionTraceArchive.maxMessages = previousGlobal
        }
    }
}
