package com.airi.assistant.data.run

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Part 5 §6. A run is the persisted header of one agent execution; events are its timeline rows.
 * Large payloads (file bodies, command output) are never stored here: only [blobRef] ids that
 * point at files in the agent workspace, plus a capped preview.
 */
@Entity(tableName = "agent_run", indices = [Index("startedAtMs"), Index("status")])
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED. */
    val status: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val model: String? = null,
    val provider: String? = null,
    val fileCount: Int = 0,
    val commandCount: Int = 0,
    val toolCount: Int = 0,
    /** Requirement checklist as JSON, e.g. [{"text":"…","state":"DONE"}]. */
    val requirementsJson: String = "[]",
    /** Workspace file ids produced by the run, comma separated. */
    val fileRefs: String = "",
    val resultPreview: String = "",
    /**
     * Part 5 §6 (batch 8): the full request and final answer, capped at [AgentRunMapper.FULL_TEXT_CAP],
     * so a run read back from Room shows what was asked and answered instead of a title and a preview.
     * Empty on rows written before schema 2; readers then fall back to [title] and [resultPreview].
     */
    @ColumnInfo(defaultValue = "''") val request: String = "",
    @ColumnInfo(defaultValue = "''") val answer: String = "",
    val executionId: String = "",
    val sessionId: String = "",
    val messageId: Long? = null,
)

@Entity(
    tableName = "agent_run_event",
    foreignKeys = [ForeignKey(
        entity = AgentRunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("runId", "sequence"), Index("parentId")],
)
data class AgentRunEventEntity(
    /** Stable key; also the LazyColumn key. */
    @PrimaryKey val eventId: String,
    val runId: String,
    val sequence: Long,
    val timestampMs: Long,
    val parentId: String? = null,
    /** UnifiedTraceCategory name. */
    val category: String,
    val type: String,
    val title: String,
    val status: String,
    val durationMs: Long? = null,
    /** Stable machine code, translated by the UI; never a raw provider error. */
    val errorCode: String? = null,
    @ColumnInfo(name = "inputPreview") val inputPreview: String = "",
    @ColumnInfo(name = "outputPreview") val outputPreview: String = "",
    /** Workspace blob id holding the full output, when it exceeded the preview cap. */
    val blobRef: String? = null,
)

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<AgentRunEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: AgentRunEventEntity): Long

    @Transaction
    suspend fun replaceRun(run: AgentRunEntity, events: List<AgentRunEventEntity>) {
        deleteEvents(run.id)
        upsertRun(run)
        upsertEvents(events)
    }

    @Query("DELETE FROM agent_run_event WHERE runId = :runId")
    suspend fun deleteEvents(runId: String)

    @Query("SELECT * FROM agent_run ORDER BY (status = 'RUNNING') DESC, startedAtMs DESC")
    fun observeRuns(): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_run WHERE id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run_event WHERE runId = :runId ORDER BY sequence ASC")
    fun observeEvents(runId: String): Flow<List<AgentRunEventEntity>>

    @Query("DELETE FROM agent_run WHERE id = :id")
    suspend fun deleteRun(id: String)

    @Query("DELETE FROM agent_run")
    suspend fun clearAll()

    /** Keeps the [keep] newest runs; events go with their run (foreign key cascade). */
    @Query("DELETE FROM agent_run WHERE id NOT IN (SELECT id FROM agent_run ORDER BY startedAtMs DESC LIMIT :keep)")
    suspend fun pruneTo(keep: Int)
}

@Database(
    entities = [AgentRunEntity::class, AgentRunEventEntity::class],
    version = AgentRunDatabase.VERSION,
    // Standalone database: its schema is not part of AiriDatabase migrations.
    exportSchema = false,
)
abstract class AgentRunDatabase : RoomDatabase() {
    abstract fun runDao(): AgentRunDao

    companion object {
        const val VERSION = 2

        /** 1 → 2: full request and answer text. Additive, so existing history is kept. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_run ADD COLUMN request TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_run ADD COLUMN answer TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
