package com.airi.assistant.memory.repository

import android.content.Context
import com.airi.assistant.ai.prompt.MemoryExtractor
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

// AP-18: applicationScope injected from ServiceLocator so summarization tasks survive
// screen rotation without relying on a ViewModel lifecycle.
class MemoryManager(context: Context, private val applicationScope: CoroutineScope? = null) {
    private val db = AiriDatabase.getDatabase(context)
    private val dao = db.memoryDao()
    private val sessionDao = db.sessionDao()
    // SupervisorJob required: without it, a single child exception cancels the
    // entire scope and all subsequent scope.launch {} calls become no-ops —
    // silently losing all memory writes for the rest of the session.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Real semantic-memory backend (). The chat path calls
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

    fun canStoreImportantMemory(content: String): Boolean =
        content.trim().isNotBlank() && !MemoryAdmissionPolicy.containsSensitiveData(content)

    fun recordInteraction(role: String, content: String, emotion: String? = null) {
        recordImportantMemory(role, content, emotion, explicitlyRequested = false)
    }

    /**
     * Stores long-term memory only when the caller has an explicit user intent.
     * System, skill and sync callers must not silently promote arbitrary content.
     */
    fun recordImportantMemory(
        role: String,
        content: String,
        emotion: String? = null,
        explicitlyRequested: Boolean = false,
        sessionId: String = "default"
    ) {
        val normalized = content.trim()
        if (!explicitlyRequested || !canStoreImportantMemory(normalized)) return
        scope.launch {
            if (dao.findLongTermMemoryId(sessionId, normalized) == null) {
                dao.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        role = role,
                        content = normalized,
                        emotionState = emotion,
                        isMemory = true
                    )
                )
                dao.pruneLongTermMemories(sessionId, MAX_LONG_TERM_FACTS_PER_SESSION)
            }
        }
    }

    suspend fun recordChatMessage(
        sessionId: String,
        role: String,
        content: String,
        emotion: String? = null,
        attachmentJson: String? = null
    ): ChatMessage {
        val draft = ChatMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            emotionState = emotion,
            isMemory = false,
            attachmentJson = attachmentJson
        )
        val messageId = dao.insertMessage(draft)
        val stored = draft.copy(id = messageId)
        sessionDao.touchSession(sessionId)

        val decision = MemoryAdmissionPolicy.decide(role, content)
        if (decision.shouldEmbed) {
            embedScope.launch {
                runCatching { embeddingService.embedAndStore(stored) }
                    .onFailure { android.util.Log.w("AIRI_MEMORY", "Embedding failed: ${it.javaClass.simpleName}") }
            }
        }

        // Keep a bounded session transcript. Long-term rows are separately
        // admitted below and never become an accidental replacement for history.
        runCatching { dao.pruneOldSessionMessages(sessionId, MAX_MESSAGES_PER_SESSION) }
            .onFailure { android.util.Log.w("AIRI_MEMORY", "Session prune failed: ${it.javaClass.simpleName}") }

        // Durable facts require an explicit memory request and then pass a
        // second allow-list. Identity, location, employer and credentials are
        // intentionally excluded from automatic long-term storage.
        if (decision.shouldExtractFacts) {
            val writeScope = applicationScope ?: scope
            writeScope.launch {
                runCatching {
                    MemoryExtractor.extract(content)
                        .filter(MemoryAdmissionPolicy::allowExtractedFact)
                        .distinct()
                        .take(MAX_LONG_TERM_FACTS_PER_TURN)
                        .forEach { fact ->
                            val storedFact = "[memory] $fact"
                            if (dao.findLongTermMemoryId(sessionId, storedFact) == null) {
                                dao.insertMessage(
                                    ChatMessage(
                                        sessionId = sessionId,
                                        role = "system",
                                        content = storedFact,
                                        isMemory = true
                                    )
                                )
                            }
                        }
                    dao.pruneLongTermMemories(sessionId, MAX_LONG_TERM_FACTS_PER_SESSION)
                }.onFailure { android.util.Log.w("AIRI_MEMORY", "Fact admission failed: ${it.javaClass.simpleName}") }
            }
        }

        return stored
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

    suspend fun getLongTermMemories(sessionId: String, limit: Int = 20): List<ChatMessage> =
        dao.getRecentLongTermMemories(sessionId, limit)

    /**
     * REAL semantic search (). Returns the top-k most similar
     * prior messages to [query] for [sessionId], ranked by cosine
     * similarity over L2-normalised vectors. Empty list if no embedding
     * model is loaded — caller can then fall back to chronological
     * recall via [getRecentMessages].
     *
     * Emits AIRI VECTOR_SEARCH_HIT (or _SKIPPED / _EMPTY).
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

    /** Releases the native embedding model under critical memory pressure. */
    suspend fun releaseEmbeddingResources() {
        embeddingService.unload()
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
        const val MAX_LONG_TERM_FACTS_PER_TURN = 2
        const val MAX_LONG_TERM_FACTS_PER_SESSION = 50
    }
}
