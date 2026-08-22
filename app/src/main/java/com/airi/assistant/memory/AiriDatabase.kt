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
        ArtifactEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(AuditLogTypeConverters::class)
abstract class AiriDatabase : RoomDatabase() {
    abstract fun memoryDao():       MemoryDao
    abstract fun sessionDao():      SessionDao
    abstract fun behaviorStatsDao(): BehaviorStatsDao
    abstract fun contextCacheDao(): ContextCacheDao
    abstract fun usageStatsDao():   UsageStatsDao
    abstract fun embeddingDao():    EmbeddingDao
    abstract fun auditLogDao():     AuditLogDao
    abstract fun artifactDao():     ArtifactDao

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
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_project_scope ON episodic_memory(projectId, memoryScope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodic_memory_expiry ON episodic_memory(expiresAtMs)")
            }
        }

        internal fun migrations(): Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        )

        fun getDatabase(context: Context): AiriDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
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
