package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.entity.UserPreference

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    /**
     * Returns the most recent N chat turns across ALL sessions, regardless of
     * whether they were tagged as long-term ("isMemory = 1") or normal chat
     * ("isMemory = 0"). The Memory screen surfaces every interaction so the
     * user can see a real, persistent history. Was previously filtering on
     * isMemory = 1 only, which made the screen look empty even after long
     * conversations (Bug #5 in the user's report).
     */
    @Query("SELECT * FROM episodic_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<ChatMessage>

    @Query("SELECT * FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessage>

    /** Explicitly accepted long-term memories for a single session. */
    @Query("SELECT * FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 1 ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentLongTermMemories(sessionId: String, limit: Int): List<ChatMessage>

    @Query("SELECT id FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 1 AND content = :content LIMIT 1")
    suspend fun findLongTermMemoryId(sessionId: String, content: String): Long?

    @Query("""
        DELETE FROM episodic_memory
        WHERE sessionId = :sessionId
          AND isMemory = 1
          AND id NOT IN (
              SELECT id FROM episodic_memory
              WHERE sessionId = :sessionId AND isMemory = 1
              ORDER BY timestamp DESC, id DESC
              LIMIT :keepRecent
          )
    """)
    suspend fun pruneLongTermMemories(sessionId: String, keepRecent: Int)

    @Query("SELECT * FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0 ORDER BY timestamp ASC")
    suspend fun getSessionMessages(sessionId: String): List<ChatMessage>

    /** Total stored interactions (user + assistant, all sessions). */
    @Query("SELECT COUNT(*) FROM episodic_memory")
    suspend fun getMemoryCount(): Int

    /** How many non-memory chat rows belong to this session. */
    @Query("SELECT COUNT(*) FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0")
    suspend fun countSessionMessages(sessionId: String): Int

    /**
     * Sliding-window pruning. Deletes the OLDEST non-memory rows of [sessionId]
     * when the session has more than [keepRecent] entries. We never delete
     * rows tagged isMemory = 1 (long-term memories surfaced by the Memory
     * screen) so the user's important interactions are preserved.
     *
     * Implemented as: keep the IDs of the most recent [keepRecent] rows in a
     * subquery, then delete everything else for this session.
     */
    @Query("""
        DELETE FROM episodic_memory
        WHERE sessionId = :sessionId
          AND isMemory = 0
          AND id NOT IN (
              SELECT id FROM episodic_memory
              WHERE sessionId = :sessionId AND isMemory = 0
              ORDER BY timestamp DESC, id DESC
              LIMIT :keepRecent
          )
    """)
    suspend fun pruneOldSessionMessages(sessionId: String, keepRecent: Int)

    /**
     * All messages for a session ordered chronologically. Alias that matches
     * the name StorageRepository delegates through — SessionDao previously held
     * this under the same name; ownership was consolidated here since MemoryDao
     * is the canonical owner of the episodic_memory table.
     */
    @Query("SELECT * FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0 ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessage>

    /**
     * Delete all non-memory rows for a session. Mirrors the method in SessionDao
     * that was used as a cross-table helper; MemoryDao owns the table so the
     * authoritative delete lives here.
     */
    @Query("DELETE FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0")
    suspend fun deleteMessagesForSession(sessionId: String)

    /**
     * Total non-memory message count across all sessions.
     * Alias matching the name StorageRepository exposes (getMessageCount).
     * The underlying count query is identical to getMemoryCount — both are kept
     * so existing callers of either name continue to compile.
     */
    @Query("SELECT COUNT(*) FROM episodic_memory")
    suspend fun getMessageCount(): Int

    /** Task 1.7: Persist thumbs up/down feedback for a message (1=like, -1=dislike, 0=none). */
    @Query("UPDATE episodic_memory SET feedback = :feedback WHERE id = :id")
    suspend fun updateMessageFeedback(id: Long, feedback: Int)

    /** Task 1.7: Retrieve feedback for a specific message. */
    @Query("SELECT feedback FROM episodic_memory WHERE id = :id LIMIT 1")
    suspend fun getMessageFeedback(id: Long): Int?

    /** Wipe everything the Memory screen displays. */
    @Query("DELETE FROM episodic_memory")
    suspend fun clearSemanticMemories()

    /** Full table wipe used by the GDPR deletion flow via StorageRepository.deleteAllData(). */
    @Query("DELETE FROM episodic_memory")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(preference: UserPreference)

    @Query("SELECT * FROM semantic_memory WHERE `key` = :key LIMIT 1")
    suspend fun getPreference(key: String): UserPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBehaviorStats(stats: BehaviorStatsEntity)

    @Query("SELECT * FROM behavior_stats")
    suspend fun getAllBehaviorStats(): List<BehaviorStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContext(context: ContextCacheEntity)

    @Query("SELECT * FROM context_cache")
    suspend fun getAllContexts(): List<ContextCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageStat(stat: UsageStatEntity)

    @Query("SELECT * FROM usage_stats")
    suspend fun getAllUsageStats(): List<UsageStatEntity>
}
