package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "episodic_memory")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "default",
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionState: String? = null,
    val isMemory: Boolean = false,
    /** Task 1.7: Persisted thumbs feedback. 1 = liked, -1 = disliked, 0 = none. */
    val feedback: Int = 0,
    /** Task 4.1: JSON-serialized attachment metadata for history display. */
    val attachmentJson: String? = null
) {
    @Ignore
    val sender: String = role
}

@Entity(tableName = "semantic_memory")
data class UserPreference(
    @PrimaryKey val key: String,
    val value: String,
    val category: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
