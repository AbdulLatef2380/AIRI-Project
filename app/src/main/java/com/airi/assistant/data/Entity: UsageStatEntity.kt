package com.airi.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_stats")
data class UsageStatEntity(
    @PrimaryKey
    val suggestionText: String,
    val usageCount: Int = 0,
    val lastUsedTimestamp: Long = System.currentTimeMillis()
)
