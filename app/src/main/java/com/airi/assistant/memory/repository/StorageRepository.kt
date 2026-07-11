package com.airi.assistant.memory.repository

import androidx.room.withTransaction
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.dao.ArtifactDao
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
 *
 * ## Architectural note — DAO API alignment
 * This file was written against the original DAO API surface. After a DAO
 * refactoring pass (method renames, signature changes, return-type changes)
 * the following call-site corrections were made to restore consistency:
 *
 *   MemoryDao:
 *     getMessagesForSession   — added to MemoryDao (was only on SessionDao)
 *     insertMessage           — return type corrected to Long on MemoryDao
 *     deleteMessagesForSession — added to MemoryDao (was cross-table helper on SessionDao)
 *     getMessageCount         — added alias on MemoryDao (was getMemoryCount)
 *     deleteAll               — added to MemoryDao
 *
 *   SessionDao:
 *     getAllSessions()         — now calls getAllSessionEntities() (plain entity list)
 *                               SessionDao.getAllSessions() was repurposed to return
 *                               ChatSessionSummary projections; getAllSessionEntities()
 *                               was added for the raw-entity path.
 *     updateTitle             — renamed to updateSessionTitle on SessionDao
 *     deleteSession(String)   — signature changed; deleteSessionById(String) used instead
 *     deleteAll               — added to SessionDao
 *
 *   AuditLogDao:
 *     deleteBefore            — renamed to pruneOlderThan on AuditLogDao
 *     deleteAll               — added to AuditLogDao
 *
 *   ContextCacheDao:
 *     deleteExpiredBefore     — renamed to cleanupOld on ContextCacheDao
 *     deleteAll               — added to ContextCacheDao
 *
 *   UsageStatsDao / BehaviorStatsDao:
 *     deleteAll               — added to each DAO
 *
 *   Transaction primitive:
 *     db.runInTransaction{}   — replaced with db.withTransaction{} (Room KTX suspend variant)
 *                               runInTransaction takes a plain Runnable; calling suspend DAO
 *                               methods inside it is a compile error.
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
    /** T28: Expose ArtifactDao through the storage facade. */
    val artifacts:     ArtifactDao      get() = db.artifactDao()

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
        withContext(Dispatchers.IO) { sessions.getAllSessionEntities() }

    suspend fun getSession(id: String): ChatSession? =
        withContext(Dispatchers.IO) { sessions.getSession(id) }

    suspend fun insertSession(session: ChatSession) =
        withContext(Dispatchers.IO) { sessions.insertSession(session) }

    suspend fun updateSessionTitle(id: String, title: String) =
        withContext(Dispatchers.IO) { sessions.updateSessionTitle(id, title) }

    /**
     * Delete a session AND all its messages atomically.
     * Uses Room's suspend-aware [withTransaction] (from room-ktx) to guarantee
     * consistency. The previous implementation used [RoomDatabase.runInTransaction]
     * which takes a plain Runnable — calling suspend DAO methods inside it is
     * a compile error. [withTransaction] is the correct primitive for suspend callers.
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            messages.deleteMessagesForSession(sessionId)
            sessions.deleteSessionById(sessionId)
        }
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    suspend fun getRecentAuditEvents(limit: Int = 100): List<AuditLogEntity> =
        withContext(Dispatchers.IO) { auditLog.getRecent(limit) }

    suspend fun insertAuditEvent(event: AuditLogEntity) =
        withContext(Dispatchers.IO) { auditLog.insert(event) }

    suspend fun deleteAuditLogsBefore(timestampMs: Long) =
        withContext(Dispatchers.IO) { auditLog.pruneOlderThan(timestampMs) }

    // ── Context cache ─────────────────────────────────────────────────────────

    suspend fun clearExpiredCache(nowMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { cache.cleanupOld(nowMs) }

    // ── Full data wipe (GDPR) ─────────────────────────────────────────────────

    /**
     * Delete ALL stored data across every Room table.
     *
     * Uses Room KTX [withTransaction] for atomicity — this is the suspend-aware
     * equivalent of [RoomDatabase.runInTransaction] and is the correct primitive
     * when the block contains suspend DAO calls.
     *
     * ── Scope ──────────────────────────────────────────────────────────────────
     * This method covers **all nine Room tables** that hold user-generated data:
     *   - episodic_memory    (chat messages — user conversations)
     *   - chat_sessions      (session index — user-created sessions)
     *   - message_embedding  (semantic vectors of user messages)
     *   - context_cache      (transient context snapshots — reproducible but user-tied)
     *   - usage_stats        (feature engagement stats — user behaviour)
     *   - behavior_stats     (agent learning stats — derived from user interactions)
     *   - audit_log          (AIRI_PROOF events — system events within user sessions)
     *   - workspace_artifact (metadata index for generated files — user-generated persistent data)
     *
     * All workspace_artifact rows are classified as user-generated persistent data:
     * they are created within user sessions, contain personal content fragments
     * (previewSnippet), and track version history of user-iterated outputs.
     *
     * ── IMPORTANT: Two-Layer Wipe ──────────────────────────────────────────────
     * workspace_artifact is a metadata index only. The actual file content lives
     * on disk at <filesDir>/workspace/artifacts/<sessionId>/<name>.<ext>.
     * This method wipes the Room index (Layer 1), but CANNOT wipe the disk files
     * (Layer 2) because StorageRepository holds no Context reference.
     *
     * Callers performing a full GDPR wipe MUST also delete the disk layer:
     *
     *   storageRepository.deleteAllData()                       // Room (this method)
     *   File(context.filesDir, "workspace/artifacts")           // Disk
     *       .deleteRecursively()
     *
     * ArtifactManager.deleteSession() already does both for per-session deletes.
     * A full wipe requires the directory-level recursive delete shown above.
     *
     * ── Note: deleteAllData() is currently not called from the GDPR flow ───────
     * PrivacyDataSettingsScreen → AuthService.deleteAccount() handles only the
     * Firebase Auth layer (server-side token revocation). This method must be
     * explicitly invoked alongside the disk wipe by whoever orchestrates the
     * full GDPR deletion.
     */
    /** Task 1.7: Persist thumbs up/down feedback for a message row. */
    suspend fun updateMessageFeedback(id: Long, feedback: Int) = withContext(Dispatchers.IO) {
        db.memoryDao().updateMessageFeedback(id, feedback)
    }

    /** Task 1.7: Get N most recent messages across all sessions (for feedback matching). */
    suspend fun getRecentMessages(limit: Int): List<com.airi.assistant.memory.entity.ChatMessage> =
        withContext(Dispatchers.IO) {
            db.memoryDao().getRecentMemories(limit)
        }

    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.memoryDao().deleteAll()
            db.sessionDao().deleteAll()
            db.embeddingDao().deleteAll()
            db.contextCacheDao().deleteAll()
            db.usageStatsDao().deleteAll()
            db.behaviorStatsDao().deleteAll()
            db.auditLogDao().deleteAll()
            db.artifactDao().deleteAll()
        }
    }
}
