package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.airi.assistant.memory.entity.MemorySyncMutationEntity

@Dao
interface MemorySyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(mutation: MemorySyncMutationEntity): Long

    @Query("SELECT * FROM memory_sync_mutations ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<MemorySyncMutationEntity>

    @Query("DELETE FROM memory_sync_mutations WHERE id IN (:ids)")
    suspend fun remove(ids: List<Long>)

    @Query("DELETE FROM memory_sync_mutations WHERE memoryId = :memoryId")
    suspend fun removeForMemory(memoryId: Long)

    @Query("SELECT COUNT(*) FROM memory_sync_mutations")
    suspend fun count(): Int
}
