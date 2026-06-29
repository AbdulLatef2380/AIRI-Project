package com.airi.assistant.memory.repository

import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.dao.AuditLogDao
import com.airi.assistant.memory.dao.BehaviorStatsDao
import com.airi.assistant.memory.dao.ContextCacheDao
import com.airi.assistant.memory.dao.EmbeddingDao
import com.airi.assistant.memory.dao.MemoryDao
import com.airi.assistant.memory.dao.SessionDao
import com.airi.assistant.memory.dao.UsageStatsDao
import com.airi.assistant.memory.entity.AuditLogEntity
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * StorageRepository — unified facade over all Room DAOs.
 *
 * Provides a single entry point for all persistent AIRI state rather than
 * having callers depend directly on individual DAOs. This enables:
 *   - Consistent error handling and logging at the repository boundary
 *   - A mock-friendly interface for tests
 *   - Cross-DAO transactions (e.g. delete session + its messages atomically)
 *   - Future migration to a different storage engine without changing callers
 *
 * All suspend functions dispatch to [Dispatchers.IO]. Callers that already
 * run on IO may omit the dispatcher override — the withContext call is
 * idempotent when already on IO.
 *
 * ## DAO access
 * Direct DAO access is still possible via the [db] property for operations
 * not yet elevated to repository-level methods.
 */
class StorageRepository(val db: AiriDatabase) {

    // ── Raw DAO access (for callers needing direct DAO operations) ────────────
    val messages:      MemoryDao        get() = db.memoryDao()
    val sessions:      SessionDao       get() = db.sessionDao()
    val auditLog:      AuditLogDao      get() = db.auditLogDao()
    val embeddings:    EmbeddingDao     get() = db.embeddingDao()
    val cache:         ContextCacheDao  get() = db.contextCacheDao()
    val usageStats:    UsageStatsDao    get() = db.usageStatsDao()
    val behaviorStats: BehaviorStatsDao get() = db.behaviorStatsDao()

    // ── Messages ──────────────────────────────────────────────────────────────

    suspend fun getMessagesForSession(sessionId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) { messages.getMessagesForSession(sessionId) }

    suspend fun insertMessage(message: ChatMessage): Long =
        withContext(Dispatchers.IO) { messages.insertMessage(message) }

    suspend fun deleteMessagesForSession(sessionId: String) =
        withContext(Dispatchers.IO) { messages.deleteMessagesForSession(sessionId) }

    suspend fun getMessageCount(): Int =
        withContext(Dispatchers.IO) { messages.getMessageCount() }

    // ── Sessions ──────────────────────────────────────────────────────────────

    suspend fun getAllSessions(): List<ChatSession> =
        withContext(Dispatchers.IO) { sessions.getAllSessions() }

    suspend fun getSession(id: String): ChatSession? =
        withContext(Dispatchers.IO) { sessions.getSession(id) }

    suspend fun insertSession(session: ChatSession) =
        withContext(Dispatchers.IO) { sessions.insertSession(session) }

    suspend fun updateSessionTitle(id: String, title: String) =
        withContext(Dispatchers.IO) { sessions.updateTitle(id, title) }

    /**
     * Delete a session AND all its messages atomically.
     * Uses a Room transaction to guarantee consistency.
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            messages.deleteMessagesForSession(sessionId)
            sessions.deleteSession(sessionId)
        }
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    suspend fun getRecentAuditEvents(limit: Int = 100): List<AuditLogEntity> =
        withContext(Dispatchers.IO) { auditLog.getRecent(limit) }

    suspend fun insertAuditEvent(event: AuditLogEntity) =
        withContext(Dispatchers.IO) { auditLog.insert(event) }

    suspend fun deleteAuditLogsBefore(timestampMs: Long) =
        withContext(Dispatchers.IO) { auditLog.deleteBefore(timestampMs) }

    // ── Context cache ─────────────────────────────────────────────────────────

    suspend fun clearExpiredCache(nowMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { cache.deleteExpiredBefore(nowMs) }

    // ── Full data wipe (GDPR) ─────────────────────────────────────────────────

    /**
     * Delete ALL stored data across every table.
     * Uses a Room transaction for atomicity.
     * Called by the GDPR deletion flow.
     */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        db.runInTransaction {
            db.memoryDao().deleteAll()
            db.sessionDao().deleteAll()
            db.embeddingDao().deleteAll()
            db.contextCacheDao().deleteAll()
            db.usageStatsDao().deleteAll()
            db.behaviorStatsDao().deleteAll()
            db.auditLogDao().deleteAll()
        }
    }
}
