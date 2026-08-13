package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessage: String?,
    val messageCount: Int
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession)

    @Update
    suspend fun updateSession(session: ChatSession)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    /** Deletes every message and explicit memory associated with a removed session. */
    @Query("DELETE FROM episodic_memory WHERE sessionId = :sessionId")
    suspend fun deleteAllRecordsForSession(sessionId: String)

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSession?

    @Query("SELECT * FROM episodic_memory WHERE sessionId = :sessionId AND isMemory = 0 ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessage>

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        SELECT s.id, s.title, s.createdAt, s.updatedAt,
            (SELECT m.content FROM episodic_memory m WHERE m.sessionId = s.id AND m.isMemory = 0 ORDER BY m.timestamp DESC LIMIT 1) AS lastMessage,
            (SELECT COUNT(*) FROM episodic_memory m WHERE m.sessionId = s.id AND m.isMemory = 0) AS messageCount
        FROM chat_sessions s
        ORDER BY s.updatedAt DESC
    """)
    suspend fun getAllSessions(): List<ChatSessionSummary>

    /**
     * All sessions as plain entities, ordered newest-first.
     *
     * The existing [getAllSessions] returns [ChatSessionSummary] (a projection with
     * computed lastMessage/messageCount columns) for UI list screens. This method
     * returns the raw [ChatSession] entity and is used by [StorageRepository.getAllSessions]
     * whose public API contract declares List<ChatSession>. Both methods are kept so
     * callers of either DAO path continue to compile.
     */
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionEntities(): List<ChatSession>

    /** Full table wipe used by the GDPR deletion flow via StorageRepository.deleteAllData(). */
    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()

    @Transaction
    suspend fun deleteSessionAndMessages(sessionId: String) {
        deleteAllRecordsForSession(sessionId)
        deleteSessionById(sessionId)
    }
}
