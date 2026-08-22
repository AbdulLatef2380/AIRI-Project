package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodic_memory",
    indices = [
        Index(name = "index_episodic_memory_project_scope", value = ["projectId", "memoryScope"]),
        Index(name = "index_episodic_memory_expiry", value = ["expiresAtMs"])
    ]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "default",
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionState: String? = null,
    val isMemory: Boolean = false,
    /** Persisted thumbs feedback. 1 = liked, -1 = disliked, 0 = none. */
    val feedback: Int = 0,
    /** JSON-serialized attachment metadata for history display. */
    val attachmentJson: String? = null,
    /** Workspace/project that owns this memory. Empty preserves legacy session-only data. */
    val projectId: String = "",
    /** Explicit origin such as USER_EXPLICIT, EXTRACTED_FACT, or CHAT_CONTEXT. */
    val memorySource: String = "CHAT_CONTEXT",
    /** Human-readable reason and source reference, never raw credentials. */
    val provenance: String = "",
    /** Admission confidence in the range 0.0–1.0. */
    val confidence: Float = 0f,
    /** Relative retention priority from 0 (low) to 100 (high). */
    val importance: Int = 0,
    /** SESSION, PROJECT, or USER. */
    val memoryScope: String = "SESSION",
    /** Maximum permitted privacy level for retrieval. */
    val privacyLevel: Int = 1,
    /** Epoch ms after which the memory is not retrieved. -1 means no expiry. */
    val expiresAtMs: Long = -1L,
    /** Epoch ms of the last successful retrieval. */
    val lastAccessedAtMs: Long = 0L,
    /** Epoch ms of the last edit or lifecycle mutation. */
    val updatedAtMs: Long = timestamp
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
