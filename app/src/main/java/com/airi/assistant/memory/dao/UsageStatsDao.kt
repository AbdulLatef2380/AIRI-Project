package com.airi.assistant.memory.dao

import androidx.room.*
import com.airi.assistant.memory.entity.UsageStatEntity

@Dao
interface UsageStatsDao {

    @Query("SELECT * FROM usage_stats")
    suspend fun getAll(): List<UsageStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: UsageStatEntity)

    @Query("UPDATE usage_stats SET usageCount = usageCount + 1, lastUsedTimestamp = :time WHERE featureName = :name")
    suspend fun incrementUsage(name: String, time: Long)
}
