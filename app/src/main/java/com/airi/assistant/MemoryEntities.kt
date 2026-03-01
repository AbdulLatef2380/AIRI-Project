package com.airi.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "episodic_memory")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" أو "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionState: String? = null,
    
    // إضافة sender كخاصية ثانوية لضمان عدم تعطل الملفات القديمة
    @androidx.room.Ignore
    val sender: String = role 
) {
    // Constructor فارغ لـ Room إذا تطلب الأمر
    constructor(role: String, content: String) : this(0, role, content, System.currentTimeMillis(), null)
}

@Entity(tableName = "semantic_memory")
data class UserPreference(
    @PrimaryKey val key: String,
    val value: String,
    val category: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
