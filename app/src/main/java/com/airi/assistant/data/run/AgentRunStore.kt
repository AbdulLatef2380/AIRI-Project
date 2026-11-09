package com.airi.assistant.data.run

import android.content.Context
import androidx.room.Room
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.core.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thin owner of [AgentRunDatabase]; readers use the DAO flows, the writer calls [record]. */
object AgentRunStore {
    /** Retention of the run store; older runs are dropped with their events. */
    const val MAX_RUNS = 200

    @Volatile private var database: AgentRunDatabase? = null
    private val liveWriteMutex = Mutex()

    fun init(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database == null) {
                database = Room.databaseBuilder(
                    context.applicationContext, AgentRunDatabase::class.java, "airi_agent_runs.db",
                )
                    .addMigrations(AgentRunDatabase.MIGRATION_1_2, AgentRunDatabase.MIGRATION_2_3)
                    // Derived data: if a future schema has no migration, rebuilding it beats crashing.
                    .fallbackToDestructiveMigration(true)
                    .build()
            }
        }
    }

    private fun dao(): AgentRunDao = checkNotNull(database) { "AgentRunStore.init was not called" }.runDao()

    /** [keep] follows the person's trace-retention level, never more than [MAX_RUNS]. */
    suspend fun record(
        trace: AgentTrace,
        previewCap: Int = AgentRunMapper.DEFAULT_PREVIEW_CAP,
        keep: Int = MAX_RUNS,
    ) {
        dao().replaceRun(AgentRunMapper.toRun(trace, previewCap), AgentRunMapper.toEvents(trace, previewCap))
        dao().pruneTo(keep.coerceIn(1, MAX_RUNS))
    }

    /**
     * Persists one canonical event while an execution is running. The event id is the idempotency
     * key; a terminal run rejects late callbacks so they cannot reopen or mutate a completed run.
     * Model-only events are intentionally ignored because ordinary chat must not become an agent run.
     */
    suspend fun recordLiveEvent(event: AgentEvent, previewCap: Int = AgentRunMapper.DEFAULT_PREVIEW_CAP) {
        if (event.executionId.isBlank() || event.type == AgentEvent.Type.MODEL_TURN) return
        liveWriteMutex.withLock {
            val store = database ?: return
            val runDao = store.runDao()
            val runId = AgentRunMapper.traceIdForExecution(event.executionId)
            val existing = runDao.observeRun(runId).first()
            if (existing != null && existing.status in TERMINAL_STATUSES) return
            val incoming = AgentRunMapper.liveRun(event)
            val run = existing?.let {
                incoming.copy(
                    title = it.title.ifBlank { incoming.title },
                    startedAtMs = minOf(it.startedAtMs, incoming.startedAtMs),
                    endedAtMs = incoming.endedAtMs ?: it.endedAtMs,
                    status = incoming.status,
                    model = it.model,
                    provider = it.provider,
                    request = it.request,
                    answer = it.answer,
                )
            } ?: incoming
            runDao.upsertRun(run)
            runDao.insertEvent(AgentRunMapper.toEvent(event, previewCap))
        }
    }

    fun observeRuns(): Flow<List<AgentRunEntity>> = dao().observeRuns()

    /** Same as [observeRuns] but empty, not a crash, when the store was never initialised. */
    fun observeRunsOrEmpty(): Flow<List<AgentRunEntity>> =
        database?.runDao()?.observeRuns() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun observeEvents(runId: String): Flow<List<AgentRunEventEntity>> = dao().observeEvents(runId)

    /** Reads one run back from Room as a read-only trace; null when absent or the store is not ready. */
    suspend fun loadTrace(id: String): AgentTrace? {
        val dao = database?.runDao() ?: return null
        val run = dao.observeRun(id).first() ?: return null
        return AgentRunRebuilder.toTrace(run, dao.observeEvents(id).first())
    }

    /** No-op when the store is not ready, so callers on the read path never throw. */
    suspend fun deleteIfReady(id: String) { database?.runDao()?.deleteRun(id) }
    suspend fun clearIfReady() { database?.runDao()?.clearAll() }

    suspend fun delete(id: String) = dao().deleteRun(id)
    suspend fun clear() = dao().clearAll()

    private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")
}
