package com.airi.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * تمثل الذاكرة العرضية (المحادثات)
 */
@Entity(tableName = "episodic_memory")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // "user" أو "airi"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotionState: String? = null // الحالة العاطفية المرتبطة بالرسالة
)

/**
 * تمثل الذاكرة الدلالية (الحقائق والتفضيلات)
 */
@Entity(tableName = "semantic_memory")
data class UserPreference(
    @PrimaryKey val key: String,
    val value: String,
    val category: String, // "personal", "system", "habit"
    val lastUpdated: Long = System.currentTimeMillis()
)
