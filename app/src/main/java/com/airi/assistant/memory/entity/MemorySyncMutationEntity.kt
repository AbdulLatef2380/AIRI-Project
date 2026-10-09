package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable local outbox entry for opt-in Firebase memory synchronization. */
@Entity(
    tableName = "memory_sync_mutations",
    indices = [Index(name = "index_memory_sync_mutations_memoryId", value = ["memoryId"])]
)
data class MemorySyncMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val memoryId: Long,
    /** UPSERT or DELETE_TOMBSTONE. */
    val operation: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)
