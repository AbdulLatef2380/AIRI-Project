package com.airi.assistant.data

import androidx.room.*

@Dao
interface ContextCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: ContextCacheEntity)

    @Query("""
        SELECT * FROM context_cache
        WHERE timestamp > :timeThreshold
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getRecentContext(timeThreshold: Long): ContextCacheEntity?

    @Query("DELETE FROM context_cache WHERE timestamp < :expireTime")
    suspend fun cleanupOld(expireTime: Long)
}
