package com.airi.assistant.memory.repository

import android.content.Context
import androidx.room.withTransaction
import com.airi.assistant.ai.prompt.MemoryExtractor
import com.airi.core.memory.MemoryAdmissionPolicy
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.json.JSONArray

// AP-18: applicationScope injected from ServiceLocator so summarization tasks survive
// screen rotation without relying on a ViewModel lifecycle.
class MemoryManager(context: Context, private val applicationScope: CoroutineScope? = null) {
    data class ExplicitMemoryRequest(
        val content: String,
        val sessionId: String = "default",
        val projectId: String = "",
        val scope: MemoryScope = if (projectId.isBlank()) MemoryScope.SESSION else MemoryScope.PROJECT,
        val privacyLevel: Int = DEFAULT_PRIVACY_LEVEL,
        val provenance: String = "Explicit user memory request",
        val importance: Int = DEFAULT_EXPLICIT_IMPORTANCE,
        val expiresAtMs: Long = NO_EXPIRY
    )

    sealed class ExplicitMemoryResult {
        data class Stored(val memoryId: Long) : ExplicitMemoryResult()
        data object Duplicate : ExplicitMemoryResult()
        data class Rejected(val reason: String) : ExplicitMemoryResult()
    }

    data class MemoryExplanation(
        val memoryId: Long,
        val source: String,
        val provenance: String,
        val scope: String,
        val projectId: String,
        val confidence: Float,
        val importance: Int,
        val privacyLevel: Int,
        val expiresAtMs: Long
    )

    private val appContext = context.applicationContext
    private val db = AiriDatabase.getDatabase(appContext)
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
        if (!explicitlyRequested) return
        scope.launch {
            storeExplicitMemory(
                ExplicitMemoryRequest(
                    content = content,
                    sessionId = sessionId,
                    provenance = "Explicit memory request via compatibility API"
                ),
                role = role,
                emotion = emotion
            )
        }
    }

    /**
     * Canonical long-term memory write path. The result is returned only after
     * policy validation, duplicate detection, Room persistence, and retention
     * pruning have completed.
     */
    suspend fun storeExplicitMemory(
        request: ExplicitMemoryRequest,
        role: String = "user",
        emotion: String? = null
    ): ExplicitMemoryResult {
        val normalized = request.content.trim()
        if (!canStoreImportantMemory(normalized)) {
            return ExplicitMemoryResult.Rejected("Content is empty or may contain sensitive data")
        }
        val normalizedScope = MemoryMetadataPolicy.normalizeScope(request.scope, request.projectId)
        val safePrivacy = MemoryMetadataPolicy.normalizePrivacyLevel(request.privacyLevel)
        val safeImportance = MemoryMetadataPolicy.normalizeImportance(request.importance)
        val existing = dao.findLongTermMemoryId(request.sessionId, normalized)
        if (existing != null) return ExplicitMemoryResult.Duplicate

        val now = System.currentTimeMillis()
        val id = dao.insertMessage(
            ChatMessage(
                sessionId = request.sessionId,
                role = role,
                content = normalized,
                timestamp = now,
                emotionState = emotion,
                isMemory = true,
                projectId = request.projectId.takeIf { normalizedScope == MemoryScope.PROJECT.name }.orEmpty(),
                memorySource = "USER_EXPLICIT",
                provenance = MemoryMetadataPolicy.sanitizeProvenance(request.provenance),
                confidence = 1f,
                importance = safeImportance,
                memoryScope = normalizedScope,
                privacyLevel = safePrivacy,
                expiresAtMs = request.expiresAtMs,
                updatedAtMs = now
            )
        )
        dao.pruneLongTermMemories(request.sessionId, MAX_LONG_TERM_FACTS_PER_SESSION)
        return ExplicitMemoryResult.Stored(id)
    }

    suspend fun recordChatMessage(
        sessionId: String,
        role: String,
        content: String,
        emotion: String? = null,
        attachmentJson: String? = null,
        projectId: String = ""
    ): ChatMessage {
        val draft = ChatMessage(
            sessionId = sessionId,
            role = role,
            content = content,
            emotionState = emotion,
            isMemory = false,
            attachmentJson = attachmentJson,
            projectId = projectId,
            memorySource = "CHAT_CONTEXT",
            provenance = "Conversation context",
            memoryScope = if (projectId.isBlank()) MemoryScope.SESSION.name else MemoryScope.PROJECT.name
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
                                val now = System.currentTimeMillis()
                                dao.insertMessage(
                                    ChatMessage(
                                        sessionId = sessionId,
                                        role = "system",
                                        content = storedFact,
                                        timestamp = now,
                                        isMemory = true,
                                        projectId = projectId,
                                        memorySource = "EXTRACTED_FACT",
                                        provenance = "Extracted from an explicit user memory request",
                                        confidence = 0.82f,
                                        importance = EXTRACTED_FACT_IMPORTANCE,
                                        memoryScope = if (projectId.isBlank()) MemoryScope.SESSION.name else MemoryScope.PROJECT.name,
                                        privacyLevel = DEFAULT_PRIVACY_LEVEL,
                                        updatedAtMs = now
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
        val attachments = sessionDao.getMessagesForSession(sessionId)
            .mapNotNull { it.attachmentJson }
        sessionDao.deleteSessionAndMessages(sessionId)
        withContext(Dispatchers.IO) { deleteAttachmentFiles(attachments) }
    }

    suspend fun deleteMessage(messageId: Long): Boolean {
        val attachmentJson = db.withTransaction {
            val message = dao.getMessageById(messageId) ?: return@withTransaction null
            dao.deleteMessageById(messageId)
            message.attachmentJson.orEmpty()
        } ?: return false

        if (attachmentJson.isNotBlank()) {
            withContext(Dispatchers.IO) { deleteAttachmentFiles(listOf(attachmentJson)) }
        }
        return true
    }

    private fun deleteAttachmentFiles(metadataEntries: List<String>) {
        val attachmentDirectory = File(appContext.filesDir, "attachments")
        metadataEntries.forEach { metadata ->
            val names = runCatching {
                val entries = JSONArray(metadata)
                buildList {
                    for (index in 0 until entries.length()) {
                        entries.optJSONObject(index)
                            ?.optString("file_name")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
            names.forEach { name ->
                val file = File(attachmentDirectory, name)
                if (file.name == name && file.isFile && !file.delete()) {
                    android.util.Log.w("AIRI_MEMORY", "Attachment cleanup failed")
                }
            }
        }
    }

    suspend fun getAllSessions(): List<ChatSessionSummary> {
        return sessionDao.getAllSessions()
    }

    suspend fun renameSession(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title)
    }

    suspend fun setSessionPinned(sessionId: String, isPinned: Boolean) {
        sessionDao.setSessionPinned(sessionId, isPinned)
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
     * Retrieves long-term memory only from scopes authorised for this task.
     * Project memory never crosses to a different project and expired rows are
     * not returned. A successful recall updates `lastAccessedAtMs` for audit.
     */
    suspend fun getScopedLongTermMemories(
        sessionId: String,
        projectId: String = "",
        maxPrivacyLevel: Int = DEFAULT_PRIVACY_LEVEL,
        limit: Int = 20
    ): List<ChatMessage> {
        val memories = dao.getScopedLongTermMemories(
            sessionId = sessionId,
            projectId = projectId,
            maxPrivacyLevel = MemoryMetadataPolicy.normalizePrivacyLevel(maxPrivacyLevel),
            nowMs = System.currentTimeMillis(),
            limit = limit.coerceIn(1, MAX_RETRIEVAL_LIMIT)
        )
        if (memories.isNotEmpty()) {
            dao.markMemoriesAccessed(memories.map(ChatMessage::id), System.currentTimeMillis())
        }
        return memories
    }

    suspend fun explainMemory(memoryId: Long): MemoryExplanation? = dao.getMessageById(memoryId)
        ?.takeIf { it.isMemory }
        ?.let { memory ->
            MemoryExplanation(
                memoryId = memory.id,
                source = memory.memorySource,
                provenance = memory.provenance,
                scope = memory.memoryScope,
                projectId = memory.projectId,
                confidence = memory.confidence,
                importance = memory.importance,
                privacyLevel = memory.privacyLevel,
                expiresAtMs = memory.expiresAtMs
            )
        }

    suspend fun forgetMemory(memoryId: Long): Boolean =
        dao.deleteLongTermMemory(memoryId) > 0

    suspend fun editMemory(
        memoryId: Long,
        content: String,
        provenance: String,
        scope: MemoryScope,
        projectId: String,
        privacyLevel: Int,
        importance: Int,
        expiresAtMs: Long
    ): Boolean {
        val existing = dao.getMessageById(memoryId)?.takeIf { it.isMemory } ?: return false
        val normalized = content.trim()
        if (!canStoreImportantMemory(normalized)) return false
        val normalizedScope = MemoryMetadataPolicy.normalizeScope(scope, projectId)
        return dao.updateLongTermMemory(
            memoryId = memoryId,
            content = normalized,
            provenance = MemoryMetadataPolicy.sanitizeProvenance(provenance),
            confidence = 1f,
            importance = MemoryMetadataPolicy.normalizeImportance(importance),
            projectId = projectId.takeIf { normalizedScope == MemoryScope.PROJECT.name }.orEmpty(),
            memoryScope = normalizedScope,
            privacyLevel = MemoryMetadataPolicy.normalizePrivacyLevel(privacyLevel),
            expiresAtMs = expiresAtMs,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    }

    /** Updates only user-authored content while preserving durable memory metadata. */
    suspend fun editMemoryContent(memoryId: Long, content: String): Boolean {
        val existing = dao.getMessageById(memoryId)?.takeIf { it.isMemory } ?: return false
        return editMemory(
            memoryId = memoryId,
            content = content,
            provenance = existing.provenance,
            scope = runCatching { MemoryScope.valueOf(existing.memoryScope) }.getOrDefault(MemoryScope.SESSION),
            projectId = existing.projectId,
            privacyLevel = existing.privacyLevel,
            importance = existing.importance,
            expiresAtMs = existing.expiresAtMs
        )
    }

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
        const val MAX_RETRIEVAL_LIMIT = 50
        const val DEFAULT_PRIVACY_LEVEL = 1
        const val DEFAULT_EXPLICIT_IMPORTANCE = 80
        const val EXTRACTED_FACT_IMPORTANCE = 65
        const val NO_EXPIRY = -1L
    }
}
