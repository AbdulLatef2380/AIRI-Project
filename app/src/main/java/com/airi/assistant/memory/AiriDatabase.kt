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
 *   v3 → v4: Added audit_log table for persistent AIRI_PROOF event storage (Phase 2 Task 5).
 *   v4 → v5: Added workspace_artifact table for ArtifactManager persistence (Phase 2 Task 26).
 *
 * ── Task 27: Database backup ──────────────────────────────────────────────────
 * [exportBackup] copies the live database file to a destination [File] using
 * Room's WAL checkpoint mechanism to ensure a consistent snapshot.
 *
 * ── Task 28: SQLCipher at-rest encryption ─────────────────────────────────────
 * ⚠️  AWAITING RUNTIME VERIFICATION — Enabling SQLCipher requires:
 *   1. Add to app/build.gradle:
 *        implementation("net.zetetic:android-database-sqlcipher:4.5.4")
 *        implementation("androidx.sqlite:sqlite-ktx:2.4.0")
 *   2. Generate a 32-byte passphrase via Android Keystore (see [buildEncryptedDatabase]).
 *   3. Set [ENCRYPTION_ENABLED] = true.
 *   4. Test on a real device — SQLCipher migration is destructive if the existing
 *      DB is unencrypted and there is no migration path provided.
 *
 * Do NOT enable [ENCRYPTION_ENABLED] in production builds until a device test
 * confirms the upgrade path (plain → encrypted) behaves correctly on your
 * target API levels and device families.
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
    version = 5,
    exportSchema = false
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

        /**
         * AP-02: SQLCipher at-rest encryption — ENABLED.
         *
         * Requires in app/build.gradle.kts (added by AP-02):
         *   implementation("net.zetetic:android-database-sqlcipher:4.5.4")
         *   implementation("androidx.sqlite:sqlite-ktx:2.4.0")
         *
         * Migration: [AiriDatabaseMigrationHelper.migrateIfNeeded] runs before
         * Room opens the database. Existing plaintext installs are upgraded
         * automatically via ATTACH/sqlcipher_export without data loss.
         */
        private const val ENCRYPTION_ENABLED = true

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

        // ── v4 → v5: workspace_artifact table (Task 26) ──────────────────────
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

        fun getDatabase(context: Context): AiriDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        private fun buildDatabase(context: Context): AiriDatabase {
            // AP-02: Run plaintext→encrypted migration BEFORE Room opens the file.
            // Safe to call on every launch — idempotent (no-op if already encrypted
            // or if this is a fresh install with no plaintext DB).
            if (ENCRYPTION_ENABLED) {
                val factory = buildSqlCipherFactory(context)
                val key = getSqlCipherKey(context)
                AiriDatabaseMigrationHelper.migrateIfNeeded(context, key)
                return Room.databaseBuilder(
                    context.applicationContext,
                    AiriDatabase::class.java,
                    "airi_memory_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .openHelperFactory(factory)
                    .build()
            }

            return Room.databaseBuilder(
                context.applicationContext,
                AiriDatabase::class.java,
                "airi_memory_db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }

        /**
         * AP-02: Returns the SQLCipher passphrase from EncryptedSharedPreferences.
         * Key is generated on first call (32 random bytes, base64-encoded) and
         * reused on all subsequent calls. The key storage uses the same
         * AES-256-GCM scheme as SecureStorage.
         */
        private fun getSqlCipherKey(context: Context): String {
            val KEY_PREFS = "airi_db_key_prefs"
            val KEY_ALIAS = "airi_db_passphrase"
            val prefs = try {
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    KEY_PREFS,
                    KEY_ALIAS,
                    context.applicationContext,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "SQLCipher: EncryptedSharedPreferences unavailable: ${e.message}")
                throw IllegalStateException("SQLCipher passphrase storage unavailable", e)
            }
            val existing = prefs.getString(KEY_ALIAS, null)
            if (existing != null) return existing
            val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val encoded = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
            prefs.edit().putString(KEY_ALIAS, encoded).apply()
            Log.i(TAG, "AIRI_PROOF DB_KEY_GENERATED new SQLCipher passphrase stored")
            return encoded
        }

        /**
         * Build a SQLCipher [SupportSQLiteOpenHelper.Factory] using a passphrase
         * derived from the Android Keystore.
         *
         * ⚠️  AWAITING RUNTIME VERIFICATION — DO NOT enable in production without device test.
         *
         * This method references `net.zetetic.database.sqlcipher.SupportFactory` which
         * requires the SQLCipher dependency in build.gradle:
         *   implementation("net.zetetic:android-database-sqlcipher:4.5.4")
         *   implementation("androidx.sqlite:sqlite-ktx:2.4.0")
         *
         * The passphrase is a 32-byte random key stored in EncryptedSharedPreferences
         * (itself backed by Android Keystore AES-256-GCM). On first run the key is
         * generated; on subsequent runs it is loaded and reused.
         */
        private fun buildSqlCipherFactory(context: Context): androidx.sqlite.db.SupportSQLiteOpenHelper.Factory {
            val passphrase = getSqlCipherKey(context).toByteArray(Charsets.UTF_8)
            // net.zetetic:android-database-sqlcipher:4.5.4 — added by AP-02 in build.gradle.kts
            return net.zetetic.database.sqlcipher.SupportFactory(passphrase)
        }

        // ── Task 27: Database backup ──────────────────────────────────────────

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

                Log.i(TAG, "AIRI_PROOF DB_BACKUP_OK dest=${destFile.absolutePath} size=${destFile.length()}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "AIRI_PROOF DB_BACKUP_FAILED: ${e.message}", e)
                false
            }
        }
    }
}
