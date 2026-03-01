package com.airi.assistant.data

import androidx.room.*

@Dao
interface ContextCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: ContextCacheEntity)

    @Query("SELECT * FROM context_cache ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ContextCacheEntity?
}
