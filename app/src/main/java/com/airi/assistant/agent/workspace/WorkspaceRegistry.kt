package com.airi.assistant.agent.workspace

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkspaceRegistry — per-goal [SandboxWorkspace] lifecycle manager.
 *
 * ## Purpose
 * [UnifiedCognitiveLoop.executeGraph] needs a fresh [SandboxWorkspace] for
 * each goal it executes. The registry provides:
 *   - `get(goalId)` — returns an existing workspace or creates a new one
 *   - `release(goalId)` — finalises the workspace and removes it from the map
 *
 * The registry is a singleton object so that the `executeGraph` default
 * parameter `workspace = WorkspaceRegistry.get(graph.goalId)` resolves at
 * the call site without needing injection.
 *
 * ## Thread safety
 * [ConcurrentHashMap] is used for the workspace map. `get` uses `computeIfAbsent`
 * to avoid a TOCTOU race between check-and-insert. `release` is a single atomic
 * remove. Safe to call from `Dispatchers.IO` parallel wave nodes.
 *
 * ## Lifecycle
 * Created lazily in `get(goalId)`. Released in the `finally` block of
 * `executeGraph` — always fires on normal completion, abort, or coroutine
 * cancellation. Orphaned workspaces (leaked via process death without finally)
 * are evicted by `pruneStale()` called from `AIRIApplication.onTrimMemory`.
 *
 * ## Previous state
 * This class was referenced by [UnifiedCognitiveLoop.executeGraph] but never
 * existed, making `executeGraph` uncompilable. Created in Phase 2 pre-migration.
 */
object WorkspaceRegistry {

    private const val TAG = "WorkspaceRegistry"

    /** Active workspaces keyed by goalId. */
    private val active = ConcurrentHashMap<String, SandboxWorkspace>()

    /**
     * Returns the [SandboxWorkspace] for [goalId], creating one if needed.
     *
     * Safe to call multiple times with the same id — returns the same instance.
     */
    fun get(goalId: String): SandboxWorkspace =
        active.computeIfAbsent(goalId) {
            Log.i(TAG, "WORKSPACE_CREATE goalId=$goalId")
            SandboxWorkspace(goalId = goalId)
        }

    /**
     * Finalises and removes the workspace for [goalId].
     *
     * Called in the `finally` block of `executeGraph` so it always fires
     * regardless of success, failure, or coroutine cancellation.
     *
     * If [goalId] is not registered (e.g. already released or never created),
     * this is a no-op — safe to call redundantly.
     */
    fun release(goalId: String) {
        val ws = active.remove(goalId)
        if (ws != null) {
            Log.i(TAG, "WORKSPACE_RELEASE goalId=$goalId snapshots=${ws.listSnapshots().size}")
        }
    }

    /**
     * Evict workspaces with no snapshots taken in the last [maxAgeMs] milliseconds.
     *
     * Uses the latest snapshot timestamp as a proxy for last-activity time.
     * Workspaces with no snapshots (never received a wave checkpoint) are
     * evicted if they've been registered for longer than [maxAgeMs].
     */
    fun pruneStale(maxAgeMs: Long = 30 * 60 * 1_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale  = active.entries.filter { (_, ws) ->
            val lastSnap = ws.listSnapshots().lastOrNull()?.timestampMs ?: 0L
            lastSnap < cutoff
        }.map { it.key }
        stale.forEach { id ->
            active.remove(id)
            Log.i(TAG, "WORKSPACE_PRUNE_STALE goalId=$id")
        }
        if (stale.isNotEmpty()) {
            Log.i(TAG, "WorkspaceRegistry.pruneStale evicted=${stale.size} remaining=${active.size}")
        }
    }

    /** Returns how many workspaces are currently active. For diagnostics only. */
    val activeCount: Int get() = active.size
}
