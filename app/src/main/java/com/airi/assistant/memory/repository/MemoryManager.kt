package com.airi.assistant.memory.repository

import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession
import com.airi.assistant.memory.entity.UserPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MemoryManager(context: Context) {
    private val db = AiriDatabase.getDatabase(context)
    private val dao = db.memoryDao()
    private val sessionDao = db.sessionDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun recordInteraction(role: String, content: String, emotion: String? = null) {
        recordImportantMemory(role, content, emotion)
    }

    fun recordImportantMemory(role: String, content: String, emotion: String? = null) {
        scope.launch {
            dao.insertMessage(ChatMessage(role = role, content = content, emotionState = emotion, isMemory = true))
        }
    }

    suspend fun recordChatMessage(sessionId: String, role: String, content: String, emotion: String? = null): ChatMessage {
        val message = ChatMessage(sessionId = sessionId, role = role, content = content, emotionState = emotion, isMemory = false)
        dao.insertMessage(message)
        sessionDao.touchSession(sessionId)
        // Issue #10 — sliding window pruning. Without this, episodic_memory
        // grows unbounded and bloats the DB (the user explicitly called this
        // out as "stores everything → no pruning"). Prune AFTER inserting so
        // we always have at least `MAX_MESSAGES_PER_SESSION` recent rows.
        // Long-term `isMemory = 1` rows are NEVER touched.
        runCatching { dao.pruneOldSessionMessages(sessionId, MAX_MESSAGES_PER_SESSION) }
            .onFailure { android.util.Log.w("AIRI_MEMORY", "prune failed: ${it.message}") }
        // Cheap proof log every 25 inserts (mod arithmetic is free).
        val count = runCatching { dao.countSessionMessages(sessionId) }.getOrDefault(-1)
        if (count >= 0 && count % 25 == 0) {
            android.util.Log.i(
                "AIRI_PROOF",
                "MEMORY_PRUNE_CHECKPOINT session=$sessionId rows=$count cap=$MAX_MESSAGES_PER_SESSION"
            )
        }
        return message
    }

    fun updatePreference(key: String, value: String, category: String = "personal") {
        scope.launch {
            dao.savePreference(UserPreference(key = key, value = value, category = category))
        }
    }

    suspend fun createSession(title: String = "New Chat"): ChatSession {
        val session = ChatSession(id = UUID.randomUUID().toString(), title = title)
        sessionDao.insertSession(session)
        return session
    }

    suspend fun ensureDefaultSession(): ChatSession {
        val existing = sessionDao.getSession("default")
        if (existing != null) return existing
        val session = ChatSession(id = "default", title = "New Chat")
        sessionDao.insertSession(session)
        return session
    }

    suspend fun loadSession(sessionId: String): List<ChatMessage> {
        return sessionDao.getMessagesForSession(sessionId)
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionAndMessages(sessionId)
    }

    suspend fun getAllSessions(): List<ChatSessionSummary> {
        return sessionDao.getAllSessions()
    }

    suspend fun renameSession(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title)
    }

    suspend fun getConversationContext(sessionId: String, limit: Int = 10): String {
        val messages = dao.getRecentMessages(sessionId, limit).reversed()
        return messages.joinToString("\n") { "${it.role}: ${it.content}" }
    }

    suspend fun getRecentMessages(sessionId: String, limit: Int = 10): List<ChatMessage> {
        return dao.getRecentMessages(sessionId, limit)
    }

    suspend fun getSemanticMemories(limit: Int = 200): List<ChatMessage> {
        return dao.getRecentMemories(limit)
    }

    suspend fun getRecentMessages(limit: Int = 10): List<ChatMessage> {
        return getSemanticMemories(limit)
    }

    suspend fun getMessageCount(): Int {
        return dao.getMemoryCount()
    }

    suspend fun clearAll() {
        dao.clearSemanticMemories()
    }

    private companion object {
        // Per-session sliding window cap. 200 chat rows ≈ 100 user/assistant
        // turns ≈ 30-50 KB of text per session — well under any reasonable
        // SQLite ceiling. The in-memory KV window (LlamaManager.maxHistory)
        // is independently capped at 4 turns.
        const val MAX_MESSAGES_PER_SESSION = 200
    }
}
