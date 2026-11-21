package com.airi.assistant.agent.durable

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.airi.assistant.memory.AiriDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Compact Room row. The versioned domain document remains the compatibility boundary. */
@Entity(
    tableName = "durable_tasks",
    indices = [
        Index(value = ["status", "updatedAtMs"], name = "index_durable_tasks_status_updatedAtMs"),
        Index(value = ["projectId", "updatedAtMs"], name = "index_durable_tasks_projectId_updatedAtMs"),
        Index(value = ["ownerId", "updatedAtMs"], name = "index_durable_tasks_ownerId_updatedAtMs"),
    ]
)
data class DurableTaskEntity(
    @PrimaryKey val taskId: String,
    val status: String,
    val ownerId: String,
    val projectId: String?,
    val queuedAtMs: Long,
    val updatedAtMs: Long,
    val stateVersion: Long,
    val payloadJson: String,
    val payloadSha256: String,
)

/** Transactional intent to dispatch work after the task row is committed. */
@Entity(
    tableName = "durable_task_outbox",
    foreignKeys = [ForeignKey(
        entity = DurableTaskEntity::class,
        parentColumns = ["taskId"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["state", "createdAtMs"], name = "index_durable_task_outbox_state_createdAtMs"),
        Index(value = ["taskId", "command"], name = "index_durable_task_outbox_taskId_command"),
    ]
)
data class DurableTaskOutboxEntity(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val command: String,
    val state: String = STATE_PENDING,
    val createdAtMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_DISPATCHED = "DISPATCHED"
        const val COMMAND_SCHEDULE_TASK = "SCHEDULE_TASK"
    }
}

/** One-time import marker; committed atomically with imported task rows. */
@Entity(tableName = "durable_task_imports")
data class DurableTaskImportEntity(
    @PrimaryKey val importId: String,
    val sourceSha256: String,
    val sourceSchemaVersion: Int,
    val sourceByteCount: Long,
    val importedTaskCount: Int,
    val importedAtMs: Long,
)

@Entity(
    tableName = "durable_task_runs",
    primaryKeys = ["taskId", "runId"],
    foreignKeys = [ForeignKey(
        entity = DurableTaskEntity::class,
        parentColumns = ["taskId"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["taskId", "startedAtMs"], name = "index_durable_task_runs_taskId_startedAtMs")],
)
data class DurableTaskRunEntity(
    val taskId: String,
    val runId: String,
    val missionId: String,
    val projectId: String?,
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val currentStepId: String?,
)

@Entity(
    tableName = "durable_task_steps",
    primaryKeys = ["taskId", "stepId"],
    foreignKeys = [ForeignKey(
        entity = DurableTaskEntity::class,
        parentColumns = ["taskId"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["taskId", "runId"], name = "index_durable_task_steps_taskId_runId"),
        Index(value = ["taskId", "status"], name = "index_durable_task_steps_taskId_status"),
    ],
)
data class DurableTaskStepEntity(
    val taskId: String,
    val stepId: String,
    val runId: String?,
    val status: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val title: String,
    val toolSummary: String,
    val error: String,
)

@Entity(
    tableName = "durable_external_operations",
    primaryKeys = ["taskId", "operationId"],
    foreignKeys = [ForeignKey(
        entity = DurableTaskEntity::class,
        parentColumns = ["taskId"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["externalStatus", "nextPollAt"], name = "index_durable_external_operations_externalStatus_nextPollAt"),
        Index(value = ["taskId", "runId", "stepId"], name = "index_durable_external_operations_taskId_runId_stepId"),
    ],
)
data class DurableExternalOperationEntity(
    val taskId: String,
    val operationId: String,
    val runId: String,
    val stepId: String,
    val externalProvider: String,
    val externalReferenceId: String?,
    val externalStatus: String,
    val startedAt: Long,
    val nextPollAt: Long?,
    val lastCheckedAt: Long?,
    val resultReference: String?,
    val deadlineAt: Long,
    val timeoutAction: String,
    val maxAttempts: Int,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
    val cancellationState: String,
    val pollAttemptCount: Int,
    val reconciliationRequiredReason: String?,
)

@Entity(
    tableName = "durable_task_events",
    primaryKeys = ["taskId", "eventId"],
    foreignKeys = [ForeignKey(
        entity = DurableTaskEntity::class,
        parentColumns = ["taskId"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["taskId", "sequence"], name = "index_durable_task_events_taskId_sequence"),
        Index(value = ["taskId", "type", "recordedAtMs"], name = "index_durable_task_events_taskId_type_recordedAtMs"),
        Index(value = ["taskId", "idempotencyKey"], name = "index_durable_task_events_taskId_idempotencyKey", unique = true),
    ],
)
data class DurableTaskEventEntity(
    val taskId: String,
    val eventId: String,
    val sequence: Long,
    val type: String,
    val summary: String,
    val detail: String,
    val runId: String?,
    val stepId: String?,
    val recordedAtMs: Long,
    val source: String = "TASK_TIMELINE",
    val idempotencyKey: String = eventId,
) {
    companion object {
        const val SOURCE_TASK_TIMELINE = "TASK_TIMELINE"
    }
}
@Dao
interface DurableTaskRoomDao {
    @Query("SELECT * FROM durable_tasks ORDER BY queuedAtMs DESC")
    fun allTasks(): List<DurableTaskEntity>

    @Query("SELECT * FROM durable_tasks WHERE taskId = :taskId LIMIT 1")
    fun task(taskId: String): DurableTaskEntity?

    @Query("SELECT COUNT(*) FROM durable_tasks")
    fun taskCount(): Int

    @Query("UPDATE durable_tasks SET stateVersion=:stateVersion WHERE taskId=:taskId AND stateVersion=0")
    fun backfillStateVersion(taskId: String, stateVersion: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTask(task: DurableTaskEntity)

    @Query("UPDATE durable_tasks SET status=:status, ownerId=:ownerId, projectId=:projectId, queuedAtMs=:queuedAtMs, updatedAtMs=:updatedAtMs, stateVersion=:newStateVersion, payloadJson=:payloadJson, payloadSha256=:payloadSha256 WHERE taskId=:taskId AND stateVersion=:expectedStateVersion")
    fun updateTask(
        taskId: String,
        status: String,
        ownerId: String,
        projectId: String?,
        queuedAtMs: Long,
        updatedAtMs: Long,
        newStateVersion: Long,
        expectedStateVersion: Long,
        payloadJson: String,
        payloadSha256: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTasks(tasks: List<DurableTaskEntity>)

    @Query("DELETE FROM durable_task_runs WHERE taskId=:taskId")
    fun deleteRuns(taskId: String)

    @Query("DELETE FROM durable_task_steps WHERE taskId=:taskId")
    fun deleteSteps(taskId: String)

    @Query("DELETE FROM durable_external_operations WHERE taskId=:taskId")
    fun deleteExternalOperations(taskId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRuns(rows: List<DurableTaskRunEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSteps(rows: List<DurableTaskStepEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertExternalOperations(rows: List<DurableExternalOperationEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvents(rows: List<DurableTaskEventEntity>)

    @Query("DELETE FROM durable_task_events WHERE taskId=:taskId")
    fun deleteEvents(taskId: String)

    @Query("SELECT * FROM durable_task_events WHERE taskId=:taskId ORDER BY sequence, eventId")
    fun events(taskId: String): List<DurableTaskEventEntity>

    @Query("SELECT COUNT(*) FROM durable_task_runs WHERE taskId=:taskId")
    fun runCount(taskId: String): Int

    @Query("SELECT COUNT(*) FROM durable_task_steps WHERE taskId=:taskId")
    fun stepCount(taskId: String): Int

    @Query("SELECT COUNT(*) FROM durable_external_operations WHERE taskId=:taskId")
    fun externalOperationCount(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOutbox(event: DurableTaskOutboxEntity): Long

    @Query("SELECT * FROM durable_task_outbox WHERE state = 'PENDING' ORDER BY createdAtMs, eventId")
    fun pendingOutbox(): List<DurableTaskOutboxEntity>

    @Query("UPDATE durable_task_outbox SET state='DISPATCHED', attemptCount=attemptCount+1, lastAttemptAtMs=:nowMs WHERE eventId=:eventId AND state='PENDING'")
    fun markOutboxDispatched(eventId: String, nowMs: Long): Int

    @Query("UPDATE durable_task_outbox SET attemptCount=attemptCount+1, lastAttemptAtMs=:nowMs WHERE eventId=:eventId AND state='PENDING'")
    fun recordOutboxAttempt(eventId: String, nowMs: Long): Int

    @Query("SELECT * FROM durable_task_imports WHERE importId=:importId LIMIT 1")
    fun importRecord(importId: String): DurableTaskImportEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertImportRecord(record: DurableTaskImportEntity)
}

/**
 * Android's durable task repository for the migration period. It never writes to,
 * renames, or deletes the source JSON. The JSON import and its marker are one
 * SQLite transaction; any parse, validation, collision, or read-back failure
 * aborts that transaction and leaves the source byte-for-byte unchanged.
 */
class DurableTaskRoomStore(
    private val database: AiriDatabase,
    private val codec: DurableTaskJsonCodec = DurableTaskJsonCodec(),
    /** Injectable only for fault-injection tests; invoked before import marker commit. */
    private val beforeImportMarker: (() -> Unit)? = null,
    /** Injectable only for fault-injection tests; invoked after task/outbox writes, before commit. */
    private val beforeOutboxCommit: (() -> Unit)? = null,
) {
    private val dao get() = database.durableTaskDao()

    fun importLegacyJsonAndLoad(sourceFile: File): DurableTaskRoomLoadResult = runBlocking {
        withContext(Dispatchers.IO) { importLegacyJsonAndLoadIo(sourceFile) }
    }

    private fun importLegacyJsonAndLoadIo(sourceFile: File): DurableTaskRoomLoadResult {
        val existingMarker = dao.importRecord(LEGACY_IMPORT_ID)
        if (existingMarker == null) {
            val sourceBytes = if (sourceFile.exists()) sourceFile.readBytes() else ByteArray(0)
            val sourceText = sourceBytes.toString(Charsets.UTF_8)
            val decoded = if (sourceBytes.isEmpty()) emptyList() else codec.decode(sourceText)
            val normalized = validateAndNormalize(decoded)
            val rows = normalized.map(::toEntity)
            val marker = DurableTaskImportEntity(
                importId = LEGACY_IMPORT_ID,
                sourceSha256 = sha256(sourceBytes),
                sourceSchemaVersion = if (sourceBytes.isEmpty()) 0 else schemaVersion(sourceText),
                sourceByteCount = sourceBytes.size.toLong(),
                importedTaskCount = rows.size,
                importedAtMs = System.currentTimeMillis(),
            )
            database.runInTransaction {
                check(dao.importRecord(LEGACY_IMPORT_ID) == null) { "Legacy task import already completed" }
                check(dao.taskCount() == 0) { "Task rows exist without a legacy import marker; refusing destructive merge" }
                if (rows.isNotEmpty()) dao.insertTasks(rows)
                val readBack = dao.allTasks()
                check(readBack.size == rows.size && readBack.associateBy { it.taskId } == rows.associateBy { it.taskId }) {
                    "Imported task rows failed in-transaction verification"
                }
                normalized.forEach { task ->
                    syncChildRows(task)
                    verifyChildCounts(task)
                }
                beforeImportMarker?.invoke()
                dao.insertImportRecord(marker)
            }
            // Verify again after commit before reporting migration success. On any mismatch, fail closed.
            val committedRows = dao.allTasks()
            verifyRows(committedRows)
            check(committedRows.size == marker.importedTaskCount) { "Committed task count differs from import marker" }
            return DurableTaskRoomLoadResult(
                tasks = committedRows.map(::fromEntity),
                sourceJsonRetained = sourceFile.exists(),
                importedNow = true,
                importedCount = marker.importedTaskCount,
                sourceSha256 = marker.sourceSha256,
            )
        }

        if (existingMarker.sourceByteCount > 0L) {
            check(sourceFile.isFile) { "Retained legacy JSON source is missing; refusing silent cutover" }
            val retainedBytes = sourceFile.readBytes()
            check(retainedBytes.size.toLong() == existingMarker.sourceByteCount &&
                sha256(retainedBytes) == existingMarker.sourceSha256
            ) { "Retained legacy JSON source changed after import; refusing silent fallback" }
        }
        val rows = dao.allTasks().map { row ->
            val decoded = codec.decode(row.payloadJson).single()
            if (row.stateVersion == 0L && decoded.stateVersion > 0L) {
                check(dao.backfillStateVersion(row.taskId, decoded.stateVersion) == 1) {
                    "Could not backfill durable task stateVersion for id=${row.taskId}"
                }
                row.copy(stateVersion = decoded.stateVersion)
            } else {
                row
            }
        }
        verifyRows(rows)
        check(rows.size == existingMarker.importedTaskCount) {
            "Durable task count differs from completed import marker; refusing writes"
        }
        return DurableTaskRoomLoadResult(
            tasks = rows.map(::fromEntity),
            sourceJsonRetained = sourceFile.exists(),
            importedNow = false,
            importedCount = existingMarker.importedTaskCount,
            sourceSha256 = existingMarker.sourceSha256,
        )
    }

    fun persist(
        task: DurableTask,
        scheduleOutbox: Boolean = false,
        expectedStateVersion: Long? = null,
    ) = runBlocking {
        withContext(Dispatchers.IO) {
            val normalized = MissionKernel.normalize(task)
            require(MissionKernel.validate(normalized) is MissionOwnershipValidation.Valid) {
                "Invalid durable task ownership"
            }
            val row = toEntity(normalized)
            database.runInTransaction {
                val existing = dao.task(row.taskId)
                if (existing == null) {
                    dao.insertTask(row)
                } else {
                    val updated = dao.updateTask(
                        taskId = row.taskId,
                        status = row.status,
                        ownerId = row.ownerId,
                        projectId = row.projectId,
                        queuedAtMs = row.queuedAtMs,
                        updatedAtMs = row.updatedAtMs,
                        newStateVersion = row.stateVersion,
                        expectedStateVersion = expectedStateVersion ?: existing.stateVersion,
                        payloadJson = row.payloadJson,
                        payloadSha256 = row.payloadSha256,
                    )
                    check(updated == 1) {
                        "Durable task CAS conflict for id=${row.taskId}; refusing stale overwrite"
                    }
                }
                syncChildRows(normalized)
                verifyChildCounts(normalized)
                if (scheduleOutbox) {
                    dao.insertOutbox(
                        DurableTaskOutboxEntity(
                            eventId = taskScheduleEventId(normalized.id),
                            taskId = normalized.id,
                            command = DurableTaskOutboxEntity.COMMAND_SCHEDULE_TASK,
                            createdAtMs = normalized.updatedAtMs,
                        )
                    )
                    beforeOutboxCommit?.invoke()
                }
            }
        }
    }

    fun pendingOutbox(): List<DurableTaskOutboxEntity> = runBlocking {
        withContext(Dispatchers.IO) { dao.pendingOutbox() }
    }

    fun markOutboxDispatched(eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean = runBlocking {
        withContext(Dispatchers.IO) { dao.markOutboxDispatched(eventId, nowMs) == 1 }
    }

    fun recordOutboxAttempt(eventId: String, nowMs: Long = System.currentTimeMillis()) = runBlocking {
        withContext(Dispatchers.IO) { dao.recordOutboxAttempt(eventId, nowMs) }
    }

    private fun validateAndNormalize(tasks: List<DurableTask>): List<DurableTask> {
        val seen = HashSet<String>(tasks.size)
        return tasks.map { task ->
            require(task.id.isNotBlank() && seen.add(task.id)) { "Legacy task IDs must be nonblank and unique" }
            val normalized = MissionKernel.normalize(task)
            require(MissionKernel.validate(normalized) is MissionOwnershipValidation.Valid) {
                "Legacy task ownership validation failed for id=${task.id}"
            }
            normalized
        }
    }

    private fun toEntity(task: DurableTask): DurableTaskEntity {
        val payload = codec.encode(listOf(task))
        return DurableTaskEntity(
            taskId = task.id,
            status = task.status.name,
            ownerId = task.ownerId,
            projectId = task.projectId,
            queuedAtMs = task.queuedAtMs,
            updatedAtMs = task.updatedAtMs,
            stateVersion = task.stateVersion,
            payloadJson = payload,
            payloadSha256 = sha256(payload.toByteArray(Charsets.UTF_8)),
        )
    }

    /** Keep normalized operational projections in lockstep with the aggregate snapshot. */
    private fun syncChildRows(task: DurableTask) {
        dao.deleteRuns(task.id)
        dao.deleteSteps(task.id)
        dao.deleteExternalOperations(task.id)
        if (task.runs.isNotEmpty()) {
            dao.insertRuns(task.runs.map { run ->
                DurableTaskRunEntity(
                    runId = run.id,
                    taskId = run.taskId,
                    missionId = run.missionId,
                    projectId = run.projectId,
                    status = run.status.name,
                    startedAtMs = run.startedAtMs,
                    finishedAtMs = run.finishedAtMs,
                    currentStepId = run.currentStepId,
                )
            })
        }
        if (task.plan.isNotEmpty()) {
            dao.insertSteps(task.plan.map { step ->
                DurableTaskStepEntity(
                    taskId = task.id,
                    stepId = step.id,
                    runId = step.runId,
                    status = step.status.name,
                    startedAtMs = step.startedAtMs,
                    completedAtMs = step.completedAtMs,
                    title = safeProjectionText(step.title),
                    toolSummary = safeProjectionText(step.toolSummary),
                    error = safeProjectionText(step.error),
                )
            })
        }
        if (task.externalOperations.isNotEmpty()) {
            dao.insertExternalOperations(task.externalOperations.map { operation ->
                DurableExternalOperationEntity(
                    taskId = task.id,
                    operationId = operation.operationId,
                    runId = operation.runId,
                    stepId = operation.stepId,
                    externalProvider = safeProjectionText(operation.externalProvider),
                    externalReferenceId = safeProjectionText(operation.externalReferenceId.orEmpty()).ifBlank { null },
                    externalStatus = operation.externalStatus.name,
                    startedAt = operation.startedAt,
                    nextPollAt = operation.nextPollAt,
                    lastCheckedAt = operation.lastCheckedAt,
                    resultReference = safeProjectionText(operation.resultReference.orEmpty()).ifBlank { null },
                    deadlineAt = operation.timeoutPolicy.deadlineAt,
                    timeoutAction = operation.timeoutPolicy.action.name,
                    maxAttempts = operation.retryPolicy.maxAttempts,
                    initialBackoffMs = operation.retryPolicy.initialBackoffMs,
                    maxBackoffMs = operation.retryPolicy.maxBackoffMs,
                    cancellationState = operation.cancellationState.name,
                    pollAttemptCount = operation.pollAttemptCount,
                    reconciliationRequiredReason = safeProjectionText(operation.reconciliationRequiredReason.orEmpty()).ifBlank { null },
                )
            })
        }
        val expectedEvents = task.timeline.mapIndexed { index, event ->
            toEventEntity(task.id, index, event)
        }
        val existingEvents = dao.events(task.id)
        if (existingEvents.size > expectedEvents.size ||
            existingEvents.zip(expectedEvents).any { (existing, expected) -> existing != expected }
        ) {
            // The domain timeline is bounded (currently 400 entries). Once the
            // head is evicted, compact the journal to the new window atomically
            // with the aggregate snapshot instead of leaving stale rows behind.
            dao.deleteEvents(task.id)
            if (expectedEvents.isNotEmpty()) dao.insertEvents(expectedEvents)
        } else if (expectedEvents.size > existingEvents.size) {
            // Normal updates append only the suffix, preserving stable event
            // identities and making retries of the same write idempotent.
            dao.insertEvents(expectedEvents.drop(existingEvents.size))
        }
    }

    private fun toEventEntity(
        taskId: String,
        index: Int,
        event: TaskTimelineEvent,
    ): DurableTaskEventEntity = DurableTaskEventEntity(
        taskId = taskId,
        eventId = "$taskId:$index",
        sequence = index.toLong(),
        type = event.type.name,
        summary = safeProjectionText(event.summary),
        detail = safeProjectionText(event.detail),
        runId = event.runId,
        stepId = event.stepId,
        recordedAtMs = event.recordedAtMs,
        source = DurableTaskEventEntity.SOURCE_TASK_TIMELINE,
        idempotencyKey = "$taskId:$index",
    )

    private fun verifyChildCounts(task: DurableTask) {
        check(dao.runCount(task.id) == task.runs.size) { "Run projection count mismatch for task=${task.id}" }
        check(dao.stepCount(task.id) == task.plan.size) { "Step projection count mismatch for task=${task.id}" }
        check(dao.externalOperationCount(task.id) == task.externalOperations.size) {
            "External operation projection count mismatch for task=${task.id}"
        }
        val journal = dao.events(task.id)
        check(journal.size == task.timeline.size) { "Event journal count mismatch for task=${task.id}" }
        val projectedTypes = DurableTaskEventProjection.rebuild(journal).map { it.type }
        check(projectedTypes == task.timeline.map { it.type }) {
            "Event journal projection diverged for task=${task.id}"
        }
    }

    private fun safeProjectionText(value: String): String {
        val compact = value.replace(Regex("\\s+"), " ").trim().take(MAX_PROJECTION_TEXT_CHARS)
        return if (SENSITIVE_PROJECTION_PATTERN.containsMatchIn(compact)) "Sensitive detail redacted" else compact
    }

    private fun fromEntity(row: DurableTaskEntity): DurableTask {
        check(sha256(row.payloadJson.toByteArray(Charsets.UTF_8)) == row.payloadSha256) {
            "Durable task payload integrity check failed for id=${row.taskId}"
        }
        val task = codec.decode(row.payloadJson).single()
        check(task.id == row.taskId && task.status.name == row.status && task.ownerId == row.ownerId &&
            task.projectId == row.projectId && task.stateVersion == row.stateVersion) {
            "Durable task indexed metadata does not match payload for id=${row.taskId}"
        }
        val normalized = MissionKernel.normalize(task)
        check(MissionKernel.validate(normalized) is MissionOwnershipValidation.Valid) {
            "Persisted task ownership validation failed for id=${row.taskId}"
        }
        return normalized
    }

    private fun verifyRows(rows: List<DurableTaskEntity>) {
        val ids = HashSet<String>(rows.size)
        rows.forEach { row ->
            check(ids.add(row.taskId)) { "Duplicate persisted task ID" }
            verifyChildCounts(fromEntity(row))
        }
    }

    private fun schemaVersion(json: String): Int = try {
        val root = com.google.gson.JsonParser.parseString(json)
        if (root.isJsonArray) 0 else root.asJsonObject.get("schemaVersion")?.asInt
            ?: throw IllegalArgumentException("Task document has no schemaVersion")
    } catch (error: Exception) {
        throw IllegalArgumentException("Cannot determine legacy task schema", error)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    data class DurableTaskRoomLoadResult(
        val tasks: List<DurableTask>,
        val sourceJsonRetained: Boolean,
        val importedNow: Boolean,
        val importedCount: Int,
        val sourceSha256: String,
    )

    companion object {
        const val LEGACY_IMPORT_ID = "durable_tasks_json_v1"
        private const val MAX_PROJECTION_TEXT_CHARS = 480
        private val SENSITIVE_PROJECTION_PATTERN = Regex(
            "(?i)(api[_ -]?key|password|secret|authorization|bearer\\s+[a-z0-9._-]+)"
        )
        private fun taskScheduleEventId(taskId: String) = "schedule-task:$taskId"
    }
}

typealias DurableTaskRoomLoadResult = DurableTaskRoomStore.DurableTaskRoomLoadResult
