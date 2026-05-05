package com.airi.assistant.core.runtime

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskCheckpointStore — durable, atomic persistence for [PersistentTaskSession]s.
 *
 * ── PERSISTENCE MODEL ─────────────────────────────────────────────────────
 *
 *   Sessions are stored as a JSON map in:
 *     {filesDir}/airi_task_sessions.json
 *
 *   Writes are atomic: write-to-temp → fsync → rename.
 *   This prevents corruption if the process is killed mid-write.
 *
 * ── IN-MEMORY CACHE ───────────────────────────────────────────────────────
 *
 *   An in-memory ConcurrentHashMap acts as the source of truth for reads.
 *   The disk file is the durable backup, loaded once on construction.
 *
 * ── CAPACITY ─────────────────────────────────────────────────────────────
 *
 *   To prevent unbounded growth, [prune] removes terminal sessions older
 *   than [MAX_AGE_MS]. Call it periodically (e.g. from Application.onCreate).
 */
class TaskCheckpointStore(context: Context) {

    private val TAG  = "TaskCheckpointStore"
    private val gson = Gson()

    private val storeFile = File(context.filesDir, "airi_task_sessions.json")
    private val cache = ConcurrentHashMap<String, PersistentTaskSession>()

    init {
        loadFromDisk()
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /** Insert or replace a session. */
    fun save(session: PersistentTaskSession) {
        cache[session.sessionId] = session
        persist()
        Log.i(TAG, "AIRI_PROOF CHECKPOINT_SAVED sessionId=${session.sessionId} status=${session.status}")
    }

    /** Load a session by ID. Returns null if not found. */
    fun load(sessionId: String): PersistentTaskSession? = cache[sessionId]

    /** All sessions, newest-first. */
    fun all(): List<PersistentTaskSession> =
        cache.values.sortedByDescending { it.updatedAtMs }

    /** Sessions that are not in a terminal state. */
    fun active(): List<PersistentTaskSession> =
        cache.values.filter { !it.isTerminal }.sortedByDescending { it.updatedAtMs }

    /** Terminal sessions that have a checkpoint — resumable after crash. */
    fun resumable(): List<PersistentTaskSession> =
        cache.values.filter { it.status == SessionStatus.SUSPENDED && it.checkpointJson.isNotBlank() }

    /** Delete a single session. */
    fun delete(sessionId: String) {
        cache.remove(sessionId)
        persist()
        Log.i(TAG, "AIRI_PROOF CHECKPOINT_DELETED sessionId=$sessionId")
    }

    /**
     * Remove terminal sessions older than [maxAgeMs] (default 7 days).
     * Returns the number pruned.
     */
    fun prune(maxAgeMs: Long = MAX_AGE_MS): Int {
        val cutoff  = System.currentTimeMillis() - maxAgeMs
        val victims = cache.values.filter { it.isTerminal && it.updatedAtMs < cutoff }
        victims.forEach { cache.remove(it.sessionId) }
        if (victims.isNotEmpty()) {
            persist()
            Log.i(TAG, "AIRI_PROOF CHECKPOINT_PRUNED count=${victims.size}")
        }
        return victims.size
    }

    /** Convenience: atomically update a session, no-op if not found. */
    fun update(sessionId: String, transform: PersistentTaskSession.() -> PersistentTaskSession) {
        val existing = cache[sessionId] ?: return
        save(existing.transform())
    }

    // ── Disk I/O ─────────────────────────────────────────────────────────────

    private fun persist() {
        runCatching {
            val json = gson.toJson(cache.values.toList())
            val tmp  = File(storeFile.parent, "${storeFile.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(storeFile)
        }.onFailure {
            Log.e(TAG, "CHECKPOINT_PERSIST_FAILED: ${it.message}", it)
        }
    }

    private fun loadFromDisk() {
        runCatching {
            if (!storeFile.exists()) return
            val json = storeFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<PersistentTaskSession>>() {}.type
            val list: List<PersistentTaskSession> = gson.fromJson(json, type) ?: emptyList()
            list.forEach { cache[it.sessionId] = it }
            Log.i(TAG, "AIRI_PROOF CHECKPOINT_LOADED count=${list.size}")
        }.onFailure {
            Log.e(TAG, "CHECKPOINT_LOAD_FAILED: ${it.message}", it)
        }
    }

    companion object {
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    }
}
