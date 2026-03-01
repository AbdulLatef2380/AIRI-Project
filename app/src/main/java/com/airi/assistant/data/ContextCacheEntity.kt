package com.airi.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "context_cache")
data class ContextCacheEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val screenText: String,
    val sourceApp: String,
    val detectedIntent: String,
    val timestamp: Long
)
