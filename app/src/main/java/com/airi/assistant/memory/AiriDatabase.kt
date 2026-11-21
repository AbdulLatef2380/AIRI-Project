package com.airi.assistant.memory

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airi.assistant.memory.dao.ArtifactDao
import com.airi.assistant.memory.dao.AuditLogDao
import com.airi.assistant.memory.dao.BehaviorStatsDao
import com.airi.assistant.memory.dao.ContextCacheDao
import com.airi.assistant.memory.dao.EmbeddingDao
import com.airi.assistant.memory.dao.MemoryDao
import com.airi.assistant.memory.dao.MemorySyncDao
import com.airi.assistant.memory.dao.SessionDao
import com.airi.assistant.memory.dao.UsageStatsDao
import com.airi.assistant.memory.entity.ArtifactEntity
import com.airi.assistant.memory.entity.AuditLogEntity
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.MessageEmbedding
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.entity.UserPreference
import com.airi.assistant.memory.entity.MemorySyncMutationEntity
import com.airi.assistant.agent.durable.DurableTaskEntity
import com.airi.assistant.agent.durable.DurableTaskOutboxEntity
import com.airi.assistant.agent.durable.DurableTaskImportEntity
import com.airi.assistant.agent.durable.DurableTaskRoomDao
import com.airi.assistant.agent.durable.DurableTaskRunEntity
import com.airi.assistant.agent.durable.DurableTaskStepEntity
import com.airi.assistant.agent.durable.DurableExternalOperationEntity
import com.airi.assistant.agent.durable.DurableTaskEventEntity
import java.io.File

/**
 * AiriDatabase — Room database for all persistent AIRI state.
 *
 * Version history:
 *   v1 → v2: Added sessionId/isMemory columns to episodic_memory; created chat_sessions table.
 *   v2 → v3: Added message_embedding table for semantic memory (RAG).
 *   v3 → v4: Added audit_log table for persistent AIRI event storage (ask 5).
 *   v4 → v5: Added workspace_artifact table for ArtifactManager persistence (ask 26).
 *   v5 → v6: Added feedback and attachment metadata columns to episodic memory.
     *   v6 → v7: Added durable chat-session pin state.
     *   v7 → v8: Added memory provenance, scope, confidence, retention, and privacy metadata.
     *   v8 → v9: Added project/task/run/step/tool/model provenance to workspace artifacts.
     *   v9 → v10: Normalized episodic-memory index names to match the Room entities.
     *   v10 → v11: Added durable opt-in memory-sync outbox mutations.
     *   v11 → v12: Added durable agent task snapshots, dispatch outbox, and legacy-import marker.
     *   v12 → v13: Added optimistic stateVersion CAS for durable task writes.
     *   v13 → v14: Added durable event source and idempotency keys.

 *
 * [exportBackup] copies the live database file to a destination [File] using
 * Room's WAL checkpoint mechanism to ensure a consistent snapshot.
 */
@Database(
    entities = [
        ChatMessage::class,
        ChatSession::class,
        UserPreference::class,
        BehaviorStatsEntity::class,
        ContextCacheEntity::class,
        UsageStatEntity::class,
        MessageEmbedding::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
        MemorySyncMutationEntity::class,
        DurableTaskEntity::class,
        DurableTaskOutboxEntity::class,
        DurableTaskImportEntity::class,
        DurableTaskRunEntity::class,
        DurableTaskStepEntity::class,
        DurableExternalOperationEntity::class,
        DurableTaskEventEntity::class
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(AuditLogTypeConverters::class)
abstract class AiriDatabase : RoomDatabase() {
    abstract fun memoryDao():       MemoryDao
    abstract fun memorySyncDao():   MemorySyncDao
    abstract fun sessionDao():      SessionDao
    abstract fun behaviorStatsDao(): BehaviorStatsDao
    abstract fun contextCacheDao(): ContextCacheDao
    abstract fun usageStatsDao():   UsageStatsDao
    abstract fun embeddingDao():    EmbeddingDao
    abstract fun auditLogDao():     AuditLogDao
    abstract fun artifactDao():     ArtifactDao
    abstract fun durableTaskDao(): DurableTaskRoomDao

    companion object {
        private const val TAG = "AiriDatabase"

        @Volatile
        private var INSTANCE: AiriDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN sessionId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN isMemory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                val now = System.currentTimeMillis()
                db.execSQL("INSERT OR IGNORE INTO chat_sessions (id, title, createdAt, updatedAt) VALUES ('default', 'Previous Chat', $now, $now)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_embedding (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        messageId INTEGER NOT NULL,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        dim INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(messageId) REFERENCES episodic_memory(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_embedding_messageId ON message_embedding(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_embedding_sessionId ON message_embedding(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_embedding_dim ON message_embedding(dim)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        tag TEXT NOT NULL,
                        message TEXT NOT NULL,
                        level TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_log_timestampMs ON audit_log(timestampMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_log_tag ON audit_log(tag)")
            }
        }

        // ── v4 → v5: workspace_artifact table () ──────────────────────
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspace_artifact (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        typeName TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        previewSnippet TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_artifact_sessionId ON workspace_artifact(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_artifact_createdAtMs ON workspace_artifact(createdAtMs)")
            }
        }

        // ── v5 → v6: feedback + attachmentJson columns (Tasks 1.7, 4.1) ─────────
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN feedback INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN attachmentJson TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN projectId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN memorySource TEXT NOT NULL DEFAULT 'CHAT_CONTEXT'")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN provenance TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN confidence REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN importance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN memoryScope TEXT NOT NULL DEFAULT 'SESSION'")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN privacyLevel INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN expiresAtMs INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN lastAccessedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodic_memory ADD COLUMN updatedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_projectId_memoryScope ON episodic_memory(projectId, memoryScope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_expiresAtMs ON episodic_memory(expiresAtMs)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN projectId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN taskId TEXT")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN runId TEXT")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN stepId TEXT")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN toolId TEXT")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN provenanceSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE workspace_artifact ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE workspace_artifact SET projectId = sessionId WHERE projectId = ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_artifact_projectId ON workspace_artifact(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_artifact_projectId_taskId_runId_stepId ON workspace_artifact(projectId, taskId, runId, stepId)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_episodic_memory_projectId_memoryScope")
                db.execSQL("DROP INDEX IF EXISTS index_episodic_memory_expiresAtMs")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_project_scope ON episodic_memory(projectId, memoryScope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_expiry ON episodic_memory(expiresAtMs)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_sync_mutations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        memoryId INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_sync_mutations_memoryId ON memory_sync_mutations(memoryId)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_tasks (
                        taskId TEXT NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        ownerId TEXT NOT NULL,
                        projectId TEXT,
                        queuedAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        payloadSha256 TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_tasks_status_updatedAtMs ON durable_tasks(status, updatedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_tasks_projectId_updatedAtMs ON durable_tasks(projectId, updatedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_tasks_ownerId_updatedAtMs ON durable_tasks(ownerId, updatedAtMs)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_task_runs (
                        taskId TEXT NOT NULL,
                        runId TEXT NOT NULL,
                        missionId TEXT NOT NULL,
                        projectId TEXT,
                        status TEXT NOT NULL,
                        startedAtMs INTEGER NOT NULL,
                        finishedAtMs INTEGER NOT NULL,
                        currentStepId TEXT,
                        PRIMARY KEY(taskId, runId),
                        FOREIGN KEY(taskId) REFERENCES durable_tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_runs_taskId_startedAtMs ON durable_task_runs(taskId, startedAtMs)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_task_steps (
                        taskId TEXT NOT NULL,
                        stepId TEXT NOT NULL,
                        runId TEXT,
                        status TEXT NOT NULL,
                        startedAtMs INTEGER NOT NULL,
                        completedAtMs INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        toolSummary TEXT NOT NULL,
                        error TEXT NOT NULL,
                        PRIMARY KEY(taskId, stepId),
                        FOREIGN KEY(taskId) REFERENCES durable_tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_steps_taskId_runId ON durable_task_steps(taskId, runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_steps_taskId_status ON durable_task_steps(taskId, status)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_external_operations (
                        taskId TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        runId TEXT NOT NULL,
                        stepId TEXT NOT NULL,
                        externalProvider TEXT NOT NULL,
                        externalReferenceId TEXT,
                        externalStatus TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        nextPollAt INTEGER,
                        lastCheckedAt INTEGER,
                        resultReference TEXT,
                        deadlineAt INTEGER NOT NULL,
                        timeoutAction TEXT NOT NULL,
                        maxAttempts INTEGER NOT NULL,
                        initialBackoffMs INTEGER NOT NULL,
                        maxBackoffMs INTEGER NOT NULL,
                        cancellationState TEXT NOT NULL,
                        pollAttemptCount INTEGER NOT NULL,
                        reconciliationRequiredReason TEXT,
                        PRIMARY KEY(taskId, operationId),
                        FOREIGN KEY(taskId) REFERENCES durable_tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_external_operations_externalStatus_nextPollAt ON durable_external_operations(externalStatus, nextPollAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_external_operations_taskId_runId_stepId ON durable_external_operations(taskId, runId, stepId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_task_events (
                        taskId TEXT NOT NULL,
                        eventId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        runId TEXT,
                        stepId TEXT,
                        recordedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(taskId, eventId),
                        FOREIGN KEY(taskId) REFERENCES durable_tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_events_taskId_sequence ON durable_task_events(taskId, sequence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_events_taskId_type_recordedAtMs ON durable_task_events(taskId, type, recordedAtMs)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_task_outbox (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        command TEXT NOT NULL,
                        state TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        lastAttemptAtMs INTEGER,
                        FOREIGN KEY(taskId) REFERENCES durable_tasks(taskId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_outbox_state_createdAtMs ON durable_task_outbox(state, createdAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_outbox_taskId_command ON durable_task_outbox(taskId, command)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS durable_task_imports (
                        importId TEXT NOT NULL PRIMARY KEY,
                        sourceSha256 TEXT NOT NULL,
                        sourceSchemaVersion INTEGER NOT NULL,
                        sourceByteCount INTEGER NOT NULL,
                        importedTaskCount INTEGER NOT NULL,
                        importedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE durable_tasks ADD COLUMN stateVersion INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE durable_task_events ADD COLUMN source TEXT NOT NULL DEFAULT 'TASK_TIMELINE'")
                db.execSQL("ALTER TABLE durable_task_events ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE durable_task_events SET idempotencyKey=eventId WHERE idempotencyKey='' ")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_durable_task_events_taskId_idempotencyKey ON durable_task_events(taskId, idempotencyKey)")
            }
        }

        internal fun migrations(): Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14
        )

        fun getDatabase(context: Context): AiriDatabase {
            return INSTANCE ?: synchronized(this) {
                // Re-check inside the monitor. Without this second check two
                // callers that observed null before entering the monitor can
                // build separate Room handles for the same SQLite file.
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AiriDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AiriDatabase::class.java,
                "airi_memory_db"
            )
                .addMigrations(*migrations())
                .build()

        // ── Database backup ──────────────────────────────────────────

        /**
         * Export a consistent snapshot of the database to [destFile].
         *
         * Uses SQLite's WAL checkpoint to flush the write-ahead log before copying,
         * ensuring the destination file is self-consistent and not mid-transaction.
         *
         * Call from a background thread / coroutine. The database MUST already be
         * open ([INSTANCE] must be non-null) before calling this method.
         *
         * @param context  Application context (for locating the DB file).
         * @param destFile Destination file. Parent directory must exist and be writable.
         * @return         true on success, false if the backup failed.
         */
        fun exportBackup(context: Context, destFile: File): Boolean {
            val db = INSTANCE ?: run {
                Log.w(TAG, "exportBackup: database not open")
                return false
            }
            return try {
                // Checkpoint WAL to ensure the main db file is up-to-date.
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")

                val dbFile = context.getDatabasePath("airi_memory_db")
                if (!dbFile.exists()) {
                    Log.w(TAG, "exportBackup: source file not found at ${dbFile.absolutePath}")
                    return false
                }

                destFile.parentFile?.mkdirs()
                dbFile.copyTo(destFile, overwrite = true)

                Log.i(TAG, "AIRI DB_BACKUP_OK dest=${destFile.absolutePath} size=${destFile.length()}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "AIRI DB_BACKUP_FAILED: ${e.message}", e)
                false
            }
        }
    }
}
