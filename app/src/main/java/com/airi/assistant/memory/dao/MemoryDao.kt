package com.airi.assistant.memory.dao

import androidx.room.*
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.UserPreference

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("SELECT * FROM episodic_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    @Query("SELECT COUNT(*) FROM episodic_memory")
    suspend fun getMessageCount(): Int

    @Query("DELETE FROM episodic_memory")
    suspend fun clearAllMessages()

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
