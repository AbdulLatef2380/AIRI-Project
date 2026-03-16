package com.airi.assistant.memory.dao

import androidx.room.*
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.UserPreference

@Dao
interface MemoryDao {

    // Episodic Memory (Chat)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("SELECT * FROM episodic_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    // Semantic Memory (Preferences)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(preference: UserPreference)

    @Query("SELECT * FROM semantic_memory WHERE `key` = :key LIMIT 1")
    suspend fun getPreference(key: String): UserPreference?

    // Behavior statistics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBehaviorStats(stats: BehaviorStatsEntity)

    @Query("SELECT * FROM behavior_stats")
    suspend fun getAllBehaviorStats(): List<BehaviorStatsEntity>

    // Context cache
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContext(context: ContextCacheEntity)

    @Query("SELECT * FROM context_cache")
    suspend fun getAllContexts(): List<ContextCacheEntity>

    // Usage statistics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageStat(stat: UsageStatEntity)

    @Query("SELECT * FROM usage_stats")
    suspend fun getAllUsageStats(): List<UsageStatEntity>
}
