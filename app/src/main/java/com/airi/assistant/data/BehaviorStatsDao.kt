package com.airi.assistant.data

import androidx.room.*

@Dao
interface BehaviorStatsDao {

    @Query("SELECT * FROM behavior_stats WHERE key = :key LIMIT 1")
    suspend fun get(key: String): BehaviorStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BehaviorStatsEntity)
}
