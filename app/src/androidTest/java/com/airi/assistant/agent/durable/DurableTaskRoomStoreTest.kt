package com.airi.assistant.agent.durable

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.memory.AiriDatabase
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableTaskRoomStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AiriDatabase::class.java)
        .allowMainThreadQueries()
        .addMigrations(*AiriDatabase.migrations())
        .build()
    private val directory = File(context.cacheDir, "durable-task-room-store-test").apply { mkdirs() }

    @After
    fun close() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun importCommitsVerifiedRowsAndLeavesOriginalJsonByteForByteUntouched() {
        val source = File(directory, "durable_tasks.json")
        val original = DurableTaskJsonCodec().encode(listOf(task("imported-task")))
            .toByteArray(StandardCharsets.UTF_8)
        source.writeBytes(original)

        val result = DurableTaskRoomStore(database).importLegacyJsonAndLoad(source)

        assertEquals(1, result.importedCount)
        assertTrue(result.importedNow)
        assertTrue(result.sourceJsonRetained)
        assertEquals(original.toList(), source.readBytes().toList())
        assertEquals("imported-task", result.tasks.single().id)
        assertEquals(1, database.durableTaskDao().taskCount())
        assertNotNull(database.durableTaskDao().importRecord(DurableTaskRoomStore.LEGACY_IMPORT_ID))

        val reopened = DurableTaskRoomStore(database).importLegacyJsonAndLoad(source)
        assertFalse(reopened.importedNow)
        assertEquals(result.tasks, reopened.tasks)
        assertEquals(original.toList(), source.readBytes().toList())
    }

    @Test
    fun injectedImportFailureRollsBackEveryRowAndPreservesOriginalSource() {
        val source = File(directory, "durable_tasks.json")
        val original = DurableTaskJsonCodec().encode(listOf(task("must-rollback")))
            .toByteArray(StandardCharsets.UTF_8)
        source.writeBytes(original)
        val store = DurableTaskRoomStore(database, beforeImportMarker = {
            throw IllegalStateException("simulated process failure before import marker")
        })

        assertTrue(runCatching { store.importLegacyJsonAndLoad(source) }.isFailure)
        assertEquals(0, database.durableTaskDao().taskCount())
        assertEquals(null, database.durableTaskDao().importRecord(DurableTaskRoomStore.LEGACY_IMPORT_ID))
        assertTrue(source.exists())
        assertEquals(original.toList(), source.readBytes().toList())
    }

    @Test
    fun taskAndScheduleIntentRollbackTogetherWhenCommitFails() {
        val store = DurableTaskRoomStore(database, beforeOutboxCommit = {
            throw IllegalStateException("simulated failure after outbox insert")
        })

        assertTrue(runCatching { store.persist(task("atomic-task"), scheduleOutbox = true) }.isFailure)
        assertEquals(0, database.durableTaskDao().taskCount())
        assertTrue(database.durableTaskDao().pendingOutbox().isEmpty())
    }

    @Test
    fun taskRunStepExternalOperationAndTimelineProjectionShareTaskTransaction() {
        val source = File(directory, "durable_tasks.json")
        source.writeText(DurableTaskJsonCodec().encode(listOf(richTask("rich-task"))))

        val imported = DurableTaskRoomStore(database).importLegacyJsonAndLoad(source).tasks.single()

        assertEquals(1, database.durableTaskDao().runCount(imported.id))
        assertEquals(1, database.durableTaskDao().stepCount(imported.id))
        assertEquals(1, database.durableTaskDao().externalOperationCount(imported.id))
        val journal = database.durableTaskDao().events(imported.id)
        assertEquals(1, journal.size)
        assertEquals(DurableTaskEventEntity.SOURCE_TASK_TIMELINE, journal.single().source)
        assertEquals("${imported.id}:0", journal.single().idempotencyKey)
    }

    @Test
    fun taskAndScheduleIntentCommitAtomicallyAndDispatchCanBeRetriedIdempotently() {
        val store = DurableTaskRoomStore(database)
        store.persist(task("atomic-success"), scheduleOutbox = true)
        store.persist(task("atomic-success"), scheduleOutbox = true)

        val first = store.pendingOutbox().single()
        assertEquals("atomic-success", first.taskId)
        assertEquals(DurableTaskOutboxEntity.COMMAND_SCHEDULE_TASK, first.command)
        assertTrue(store.markOutboxDispatched(first.eventId))
        assertTrue(store.pendingOutbox().isEmpty())
        assertFalse(store.markOutboxDispatched(first.eventId))
        assertEquals(1, database.durableTaskDao().taskCount())
    }

    @Test
    fun staleWriterIsRejectedByStateVersionCasWithoutChangingCommittedPayload() {
        val source = File(directory, "cas-legacy.json")
        val initial = task("cas-task")
        source.writeText(DurableTaskJsonCodec().encode(listOf(initial)))
        val store = DurableTaskRoomStore(database)
        store.importLegacyJsonAndLoad(source)

        val committed = initial.copy(
            stateVersion = 1L,
            progressMessage = "writer-one",
            updatedAtMs = initial.updatedAtMs + 1L,
        )
        store.persist(committed, expectedStateVersion = 0L)

        val stale = initial.copy(
            stateVersion = 1L,
            progressMessage = "stale-writer",
            updatedAtMs = initial.updatedAtMs + 2L,
        )
        assertTrue(runCatching {
            store.persist(stale, expectedStateVersion = 0L)
        }.isFailure)

        val loaded = store.importLegacyJsonAndLoad(source).tasks.single()
        assertEquals(1L, loaded.stateVersion)
        assertEquals("writer-one", loaded.progressMessage)
    }

    @Test
    fun corruptOrFutureJsonIsRejectedWithoutMarkerOrSourceMutation() {
        listOf("{broken", "{\"schemaVersion\":999,\"tasks\":[]}").forEachIndexed { index, contents ->
            val isolatedDatabase = Room.inMemoryDatabaseBuilder(context, AiriDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(*AiriDatabase.migrations())
                .build()
            val source = File(directory, "invalid-$index.json").apply { writeText(contents) }
            try {
                assertTrue(runCatching { DurableTaskRoomStore(isolatedDatabase).importLegacyJsonAndLoad(source) }.isFailure)
                assertEquals(0, isolatedDatabase.durableTaskDao().taskCount())
                assertEquals(null, isolatedDatabase.durableTaskDao().importRecord(DurableTaskRoomStore.LEGACY_IMPORT_ID))
                assertTrue(source.exists())
                assertEquals(contents, source.readText())
            } finally {
                isolatedDatabase.close()
            }
        }
    }

    private fun task(id: String) = DurableTask(
        id = id,
        title = "Room migration fixture",
        description = "A task used by durable storage instrumentation tests",
        agentId = "test-agent",
        input = "safe test input",
        queuedAtMs = 1_700_000_000_000L,
        updatedAtMs = 1_700_000_000_000L,
    )

    private fun richTask(id: String): DurableTask {
        val base = task(id)
        val runId = "run-$id"
        val stepId = "step-$id"
        return base.copy(
            missionId = "mission-$id",
            status = DurableTaskStatus.RUNNING,
            startedAtMs = 1_700_000_000_000L,
            currentRunId = runId,
            currentStepId = stepId,
            runs = listOf(
                TaskRun(
                    id = runId,
                    taskId = id,
                    missionId = "mission-$id",
                    startedAtMs = 1_700_000_000_000L,
                    status = TaskRunStatus.RUNNING,
                )
            ),
            plan = listOf(
                TaskPlanStep(
                    id = stepId,
                    title = "Run durable CI",
                    runId = runId,
                    status = TaskStepStatus.WAITING_EXTERNAL,
                    startedAtMs = 1_700_000_000_000L,
                )
            ),
            externalOperations = listOf(
                ExternalOperation(
                    operationId = "operation-$id",
                    taskId = id,
                    runId = runId,
                    stepId = stepId,
                    externalProvider = "fake-ci",
                    externalReferenceId = "job-123",
                    startedAt = 1_700_000_000_000L,
                    nextPollAt = 1_700_000_060_000L,
                )
            ),
            timeline = listOf(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.EXTERNAL_OPERATION_STARTED,
                    summary = "External CI started",
                    runId = runId,
                    stepId = stepId,
                    recordedAtMs = 1_700_000_000_000L,
                )
            ),
        )
    }
}
