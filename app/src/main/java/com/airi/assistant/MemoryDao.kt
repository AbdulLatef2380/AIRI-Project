package com.airi.assistant

import androidx.room.*

@Dao
interface MemoryDao {

    // عمليات الذاكرة العرضية
    @Insert
    suspend fun insertMessage(message: ChatMessage)

    @Query("SELECT * FROM episodic_memory ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentMessages(): List<ChatMessage>

    // عمليات الذاكرة الدلالية
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(preference: UserPreference)

    @Query("SELECT * FROM semantic_memory WHERE `key` = :key")
    suspend fun getPreference(key: String): UserPreference?

    @Query("SELECT * FROM semantic_memory")
    suspend fun getAllPreferences(): List<UserPreference>
}
