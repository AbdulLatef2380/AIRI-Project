package com.airi.assistant.memory.dao

import androidx.room.*
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.UsageStatEntity

@Dao
interface MemoryDao {

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
