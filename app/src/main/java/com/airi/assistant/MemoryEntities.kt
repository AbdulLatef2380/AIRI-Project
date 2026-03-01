package com.airi.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Ignore

@Entity(tableName = "episodic_memory")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,        // نستخدم role ليتوافق مع معايير Llama 3
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionState: String? = null
) {
    // هذا السطر يضمن أن أي كود قديم يبحث عن 'sender' سيجد القيمة في 'role'
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
