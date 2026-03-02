package com.airi.assistant.data

import androidx.room.*

@Dao
interface UsageStatsDao {

    @Query("SELECT * FROM usage_stats")
    suspend fun getAll(): List<UsageStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: UsageStatEntity)

    @Query("UPDATE usage_stats SET usageCount = usageCount + 1, lastUsedTimestamp = :time WHERE featureName = :name")
    suspend fun incrementUsage(name: String, time: Long)
}
