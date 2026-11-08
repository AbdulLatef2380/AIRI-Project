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
 * existed, making `executeGraph` uncompilable. Created in  pre-migration.
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
            safeLog("WORKSPACE_CREATE goalId=$goalId")
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
            safeLog("WORKSPACE_RELEASE goalId=$goalId artifacts=${ws.summary().fileCount}")
        }
    }

    /**
     * Evict workspaces with no activity in the last [maxAgeMs] milliseconds.
     */
    fun pruneStale(maxAgeMs: Long = 30 * 60 * 1_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale  = active.entries.filter { (_, ws) ->
            ws.lastActivityMs < cutoff
        }.map { it.key }
        stale.forEach { id ->
            active.remove(id)
            safeLog("WORKSPACE_PRUNE_STALE goalId=$id")
        }
        if (stale.isNotEmpty()) {
            safeLog("WorkspaceRegistry.pruneStale evicted=${stale.size} remaining=${active.size}")
        }
    }

    /** Android's platform Log throws in plain JVM tests; never let diagnostics
     * change the lifecycle contract or make a workspace operation fail. */
    private fun safeLog(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    /** Returns how many workspaces are currently active. For diagnostics only. */
    val activeCount: Int get() = active.size
}
