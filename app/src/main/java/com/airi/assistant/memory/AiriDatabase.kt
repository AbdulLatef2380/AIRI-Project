package com.airi.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airi.assistant.memory.dao.BehaviorStatsDao
import com.airi.assistant.memory.dao.ContextCacheDao
import com.airi.assistant.memory.dao.EmbeddingDao
import com.airi.assistant.memory.dao.MemoryDao
import com.airi.assistant.memory.dao.SessionDao
import com.airi.assistant.memory.dao.UsageStatsDao
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.MessageEmbedding
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.entity.UserPreference

@Database(
    entities = [
        ChatMessage::class,
        ChatSession::class,
        UserPreference::class,
        BehaviorStatsEntity::class,
        ContextCacheEntity::class,
        UsageStatEntity::class,
        MessageEmbedding::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AiriDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun sessionDao(): SessionDao
    abstract fun behaviorStatsDao(): BehaviorStatsDao
    abstract fun contextCacheDao(): ContextCacheDao
    abstract fun usageStatsDao(): UsageStatsDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
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

        // v3 — adds the message_embedding table for semantic memory. The
        // foreign key onto episodic_memory uses ON DELETE CASCADE so
        // pruning a chat row also removes its vector (no orphan rows).
        // Two indices are created up-front: one unique on messageId
        // (one vector per message) and one on sessionId (the search
        // hot path filters by session).
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

        fun getDatabase(context: Context): AiriDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AiriDatabase::class.java,
                    "airi_memory_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
