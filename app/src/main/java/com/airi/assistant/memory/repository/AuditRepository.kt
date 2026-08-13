package com.airi.assistant.memory.repository

import android.util.Log
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.AuditLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * AuditRepository — single entry point for writing and querying the
 * persistent AIRI_RUNTIME audit log.
 *
 * ── Phase 2, Task 5 ────────────────────────────────────────────────────────
 * This class bridges the logcat-only AIRI_RUNTIME system to Room persistence.
 * All callers that previously only wrote `Log.i(TAG, "AIRI_RUNTIME …")` can now
 * also call [log] to persist the event to the database for post-mortem analysis.
 *
 * ── Usage ──────────────────────────────────────────────────────────────────
 * Obtain the singleton from [com.airi.assistant.core.ServiceLocator]:
 *
 *   ServiceLocator.auditRepository.log("THERMAL", "…", Level.WARN)
 *
 * ── Thread safety ──────────────────────────────────────────────────────────
 * [log] is non-blocking (fire-and-forget via a SupervisorJob scope).
 * The caller never blocks; IO failures are logged to logcat only (fail-open
 * by design — a broken audit trail must never block the main execution path).
 *
 * ── Retention ──────────────────────────────────────────────────────────────
 * [pruneOldEntries] is called lazily after every [PRUNE_AFTER_WRITES] writes.
 * It deletes rows older than [AuditLogEntity.MAX_RETENTION_DAYS] days.
 */
class AuditRepository(private val db: AiriDatabase) {

    private val dao   = db.auditLogDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writeCount = 0

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Persist one audit event. Fire-and-forget — never throws on the caller.
     *
     * Also emits a logcat line with the canonical AIRI_RUNTIME prefix so the
     * existing logcat grep-based debugging workflow continues to work unchanged.
     *
     * @param tag     Module identifier (e.g. "THERMAL", "AUTH", "FIREWALL").
     * @param message Full event description.
     * @param level   Severity. Defaults to [AuditLogEntity.Level.INFO].
     */
    fun log(
        tag:     String,
        message: String,
        level:   AuditLogEntity.Level = AuditLogEntity.Level.INFO
    ) {
        // Mirror to logcat so existing log-grep workflows continue to work.
        val logcatMsg = "AIRI_RUNTIME $tag $message"
        when (level) {
            AuditLogEntity.Level.VERBOSE -> Log.v(TAG, logcatMsg)
            AuditLogEntity.Level.DEBUG   -> Log.d(TAG, logcatMsg)
            AuditLogEntity.Level.INFO    -> Log.i(TAG, logcatMsg)
            AuditLogEntity.Level.WARN    -> Log.w(TAG, logcatMsg)
            AuditLogEntity.Level.ERROR   -> Log.e(TAG, logcatMsg)
        }

        scope.launch {
            runCatching {
                dao.insert(
                    AuditLogEntity(
                        tag       = tag,
                        message   = message,
                        level     = level
                    )
                )
                writeCount++
                if (writeCount % PRUNE_AFTER_WRITES == 0) {
                    pruneOldEntries()
                }
            }.onFailure { e ->
                Log.e(TAG, "AuditRepository.log DB write failed: ${e.message}")
            }
        }
    }

    // ── Convenience overloads ─────────────────────────────────────────────────

    fun info (tag: String, message: String) = log(tag, message, AuditLogEntity.Level.INFO)
    fun warn (tag: String, message: String) = log(tag, message, AuditLogEntity.Level.WARN)
    fun error(tag: String, message: String) = log(tag, message, AuditLogEntity.Level.ERROR)
    fun debug(tag: String, message: String) = log(tag, message, AuditLogEntity.Level.DEBUG)

    // ── Read API ──────────────────────────────────────────────────────────────

    suspend fun getRecent(limit: Int = 200): List<AuditLogEntity> =
        dao.getRecent(limit)

    suspend fun getByTag(tag: String, limit: Int = 100): List<AuditLogEntity> =
        dao.getByTag(tag, limit)

    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> =
        dao.observeRecent(limit)

    suspend fun getErrors(limit: Int = 100): List<AuditLogEntity> =
        dao.getByLevels(
            listOf(AuditLogEntity.Level.WARN.name, AuditLogEntity.Level.ERROR.name),
            limit
        )

    suspend fun count(): Long = dao.count()

    // ── Maintenance ───────────────────────────────────────────────────────────

    suspend fun pruneOldEntries() {
        val cutoff = System.currentTimeMillis() -
                TimeUnit.DAYS.toMillis(AuditLogEntity.MAX_RETENTION_DAYS)
        runCatching { dao.pruneOlderThan(cutoff) }
            .onFailure { Log.e(TAG, "AuditRepository prune failed: ${it.message}") }
    }

    /**
     * AP-11: Explicit cutoff pruner called by [ScheduledJobOrchestrator] audit_log_pruner job.
     * Deletes all rows older than [cutoffMs] (epoch millis). Does not use MAX_RETENTION_DAYS
     * so the orchestrator can configure any retention window independently.
     */
    suspend fun pruneOlderThan(cutoffMs: Long) {
        runCatching { dao.pruneOlderThan(cutoffMs) }
            .onFailure { Log.e(TAG, "AuditRepository.pruneOlderThan failed: ${it.message}") }
    }

    private companion object {
        const val TAG              = "AIRI_AuditRepository"
        const val PRUNE_AFTER_WRITES = 500
    }
}
