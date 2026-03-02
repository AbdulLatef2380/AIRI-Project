package com.airi.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_stats")
data class UsageStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val featureName: String,
    val usageCount: Int, // ✅ تم التعديل من useCount إلى usageCount
    val lastUsedTimestamp: Long
)
