package com.airi.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "behavior_stats")
data class BehaviorStatsEntity(
    @PrimaryKey val key: String, // app + intent
    val shownCount: Int = 0,
    val acceptedCount: Int = 0,
    val dismissedCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
