package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AuditLogEntity — persistent AIRI audit record.
 *
 * ── , Persist AIRI to Room ────────────────────────────
 * Previously all AIRI events lived only in logcat and were lost on
 * process death, making post-incident forensic analysis impossible without an
 * active ADB connection.
 *
 * Each call to [AuditRepository.log] writes one row here. The table is indexed
 * on [timestampMs] for efficient time-range queries, and on [tag] for filtering
 * by module (THERMAL, AUTH, SKILL, etc.).
 *
 * Retention: rows older than [MAX_RETENTION_DAYS] are purged by the reaper
 * called in [AuditRepository]. This prevents unbounded table growth.
 *
 * Security: the table sits in the same Room database as the rest of AIRI's
 * state. When SQLCipher is enabled (, Task future) the entire DB
 * (including this table) will be encrypted at rest.
 */
@Entity(
    tableName = "audit_log",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["tag"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Module tag, e.g. "THERMAL_PROFILER", "AUTH", "FIREWALL". */
    val tag: String,

    /** Full AIRI message text. */
    val message: String,

    /** Severity level for filtering — mirrors Android log priorities. */
    val level: Level,

    /** Unix epoch ms. Indexed for time-range queries. */
    val timestampMs: Long = System.currentTimeMillis()
) {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    companion object {
        /** Rows older than this are eligible for pruning by the reaper. */
        const val MAX_RETENTION_DAYS = 30L
    }
}
