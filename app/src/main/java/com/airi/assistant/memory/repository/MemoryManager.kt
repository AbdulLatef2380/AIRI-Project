package com.airi.assistant.memory.repository

import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.memory.embedding.EmbeddingService
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession
import com.airi.assistant.memory.entity.UserPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class MemoryManager(context: Context) {
    private val db = AiriDatabase.getDatabase(context)
    private val dao = db.memoryDao()
    private val sessionDao = db.sessionDao()
    // SupervisorJob required: without it, a single child exception cancels the
    // entire scope and all subsequent scope.launch {} calls become no-ops —
    // silently losing all memory writes for the rest of the session.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Real semantic-memory backend (Phase 2). The chat path calls
     * [recordChatMessage] which fires-and-forgets [embeddingService.embedAndStore]
     * on a supervisor scope so a failed embed cannot block the chat insert.
     * If no embedding model is loaded, the embed is a logged no-op.
     *
     * Exposed (`internal`) so [com.airi.assistant.ui.viewmodel.ChatViewModel]
     * can call the token-budget aware [EmbeddingService.formatContextWithBudget]
     * during prompt assembly without needing to duplicate the singleton lookup.
     */
    internal val embeddingService = EmbeddingService.getInstance(context)
    private val embedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        // Room's @Insert returns Unit for autoGenerate=true PKs unless we
        // change the DAO signature; instead we re-read the row's assigned
        // id by fetching the most recent insert via touchSession's
        // companion semantics. Cleaner: insert and then look up the row
        // we know is the newest for this (session, timestamp).
        dao.insertMessage(message)
        sessionDao.touchSession(sessionId)
        // Fire-and-forget embedding compute. The dao auto-generates the
        // PK; we re-fetch the just-inserted row by (sessionId, timestamp)
        // tail, which is guaranteed unique because timestamp is millis +
        // we just inserted exactly one row for this session.
        embedScope.launch {
            runCatching {
                val recents = dao.getRecentMessages(sessionId, 1)
                val stored = recents.firstOrNull() ?: return@launch
                embeddingService.embedAndStore(stored)
            }.onFailure {
                android.util.Log.w("AIRI_MEMORY", "fire-and-forget embed failed: ${it.message}")
            }
        }
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

    /**
     * REAL semantic search (Phase 2). Returns the top-k most similar
     * prior messages to [query] for [sessionId], ranked by cosine
     * similarity over L2-normalised vectors. Empty list if no embedding
     * model is loaded — caller can then fall back to chronological
     * recall via [getRecentMessages].
     *
     * Emits AIRI_PROOF VECTOR_SEARCH_HIT (or _SKIPPED / _EMPTY).
     */
    suspend fun semanticSearch(sessionId: String, query: String, k: Int = 5):
        List<EmbeddingService.RankedMessage> =
            embeddingService.topKSimilar(sessionId, query, k)

    /**
     * Build a "Relevant prior context" block for splicing into a system
     * prompt. Returns "" if no hits — caller can append unconditionally.
     */
    suspend fun buildSemanticContext(sessionId: String, query: String, k: Int = 5): String {
        val hits = semanticSearch(sessionId, query, k)
        return embeddingService.formatContext(hits)
    }

    /** Whether the embedding backend is loaded and ready. */
    fun isSemanticMemoryReady(): Boolean = embeddingService.isReady()

    suspend fun getRecentMessages(limit: Int = 10): List<ChatMessage> {
        return getSemanticMemories(limit)
    }

    suspend fun getMessageCount(): Int {
        return dao.getMemoryCount()
    }

    suspend fun clearAll() {
        dao.clearSemanticMemories()
    }

    suspend fun deleteMessageById(id: Long) {
        dao.deleteById(id)
    }

    private companion object {
        // Per-session sliding window cap. 200 chat rows ≈ 100 user/assistant
        // turns ≈ 30-50 KB of text per session — well under any reasonable
        // SQLite ceiling. The in-memory KV window (LlamaManager.maxHistory)
        // is independently capped at 4 turns.
        const val MAX_MESSAGES_PER_SESSION = 200
    }
}
