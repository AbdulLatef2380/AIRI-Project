package com.airi.assistant.memory.dao

import androidx.room.*
import com.airi.assistant.memory.entity.BehaviorStatsEntity

@Dao
interface BehaviorStatsDao {

    @Query("SELECT * FROM behavior_stats WHERE key = :key LIMIT 1")
    suspend fun get(key: String): BehaviorStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BehaviorStatsEntity)
}
