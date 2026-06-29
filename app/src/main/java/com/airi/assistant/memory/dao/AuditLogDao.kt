package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.airi.assistant.memory.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * AuditLogDao — Room DAO for [AuditLogEntity].
 *
 * All write operations are suspend functions (off-main-thread).
 * Query functions are both one-shot and Flow-backed for observability screens.
 */
@Dao
interface AuditLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    /** All entries for the given tag, newest first. */
    @Query("""
        SELECT * FROM audit_log
        WHERE tag = :tag
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    suspend fun getByTag(tag: String, limit: Int = 100): List<AuditLogEntity>

    /** Most recent N entries across all tags, newest first. */
    @Query("""
        SELECT * FROM audit_log
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    suspend fun getRecent(limit: Int = 200): List<AuditLogEntity>

    /** Live query — emits whenever any new row is inserted. */
    @Query("""
        SELECT * FROM audit_log
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>>

    /** Entries within the given time window, newest first. */
    @Query("""
        SELECT * FROM audit_log
        WHERE timestampMs >= :fromMs AND timestampMs <= :toMs
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    suspend fun getInRange(fromMs: Long, toMs: Long, limit: Int = 500): List<AuditLogEntity>

    /** Entries at or above the given severity level, newest first. */
    @Query("""
        SELECT * FROM audit_log
        WHERE level IN (:levels)
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    suspend fun getByLevels(levels: List<String>, limit: Int = 200): List<AuditLogEntity>

    /** Prune rows older than [beforeMs] to enforce retention policy. */
    @Query("DELETE FROM audit_log WHERE timestampMs < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Long
}
