package com.airi.assistant.agent.workspace

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// SandboxWorkspace — virtual file system + persistent action log with rollback
//
// Exposes a per-goal isolated working area where the agent can:
//   • Create, read, update, and delete "files" (keyed by path, in-memory).
//   • Append typed ActionLog entries for every tool call / decision.
//   • Snapshot the entire workspace before destructive operations.
//   • Roll back to the last snapshot on error.
//
// Design decisions:
//   • All state is in-memory for this session; persistence layer can be added
//     by serialising workspaces to Room/SharedPreferences later.
//   • Action logs are immutable after creation (append-only).
//   • Snapshots are copy-on-write (shallow copies of the file map).
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "SandboxWorkspace"
private const val MAX_ACTION_LOG = 500
private const val MAX_SNAPSHOTS  = 10

// ── Action log entry ──────────────────────────────────────────────────────────

enum class ActionCategory { TOOL_CALL, FILE_OP, DECISION, EXTERNAL, POLICY_CHECK, ERROR }

data class ActionLogEntry(
    val id:          Long            = System.nanoTime(),
    val timestampMs: Long            = System.currentTimeMillis(),
    val category:    ActionCategory,
    val actor:       String,
    val action:      String,
    val params:      Map<String, String> = emptyMap(),
    val result:      String?         = null,
    val success:     Boolean         = true
) {
    val formattedTime: String
        get() {
            val ms = System.currentTimeMillis() - timestampMs
            return if (ms < 1000) "${ms}ms ago" else "${ms / 1000}s ago"
        }
}

// ── Workspace snapshot ────────────────────────────────────────────────────────

data class SandboxSnapshot(
    val id:          String = "snap_${System.currentTimeMillis()}",
    val createdAtMs: Long   = System.currentTimeMillis(),
    val files:       Map<String, String>,
    val logSize:     Int
)

// ── Workspace result types ────────────────────────────────────────────────────

sealed class WorkspaceResult {
    data class Ok(val content: String) : WorkspaceResult()
    data class Err(val reason: String) : WorkspaceResult()
    object NotFound : WorkspaceResult()
}

// ── Workspace ─────────────────────────────────────────────────────────────────

class SandboxWorkspace(val goalId: String) {

    private val files     = ConcurrentHashMap<String, String>()
    private val actionLog = mutableListOf<ActionLogEntry>()
    private val snapshots = ArrayDeque<SandboxSnapshot>(MAX_SNAPSHOTS)

    // ── File system ───────────────────────────────────────────────────────────

    /** Write (create or overwrite) a virtual file at [path]. */
    fun writeFile(path: String, content: String, actor: String = "agent"): WorkspaceResult {
        val normalised = normalisePath(path)
        files[normalised] = content
        log(ActionCategory.FILE_OP, actor, "write", mapOf("path" to normalised, "bytes" to content.length.toString()), "ok")
        Log.d(TAG, "WORKSPACE_WRITE goal=$goalId path=$normalised bytes=${content.length}")
        return WorkspaceResult.Ok(content)
    }

    /** Append [content] to an existing file, creating it if absent. */
    fun appendFile(path: String, content: String, actor: String = "agent"): WorkspaceResult {
        val normalised = normalisePath(path)
        val existing   = files[normalised] ?: ""
        val merged     = existing + content
        files[normalised] = merged
        log(ActionCategory.FILE_OP, actor, "append", mapOf("path" to normalised, "addedBytes" to content.length.toString()), "ok")
        return WorkspaceResult.Ok(merged)
    }

    /** Read a virtual file. Returns [WorkspaceResult.NotFound] if absent. */
    fun readFile(path: String): WorkspaceResult {
        val normalised = normalisePath(path)
        return files[normalised]?.let { WorkspaceResult.Ok(it) } ?: WorkspaceResult.NotFound
    }

    /** Delete a file. Returns true if it existed. */
    fun deleteFile(path: String, actor: String = "agent"): Boolean {
        val normalised = normalisePath(path)
        val existed    = files.remove(normalised) != null
        log(ActionCategory.FILE_OP, actor, "delete", mapOf("path" to normalised, "existed" to existed.toString()), if (existed) "ok" else "not_found")
        return existed
    }

    /** List all paths currently held in the workspace. */
    fun listFiles(): List<String> = files.keys.sorted()

    /** True if a file exists at [path]. */
    fun exists(path: String): Boolean = files.containsKey(normalisePath(path))

    // ── Action log ────────────────────────────────────────────────────────────

    fun logToolCall(actor: String, tool: String, params: Map<String, String> = emptyMap(), result: String? = null, success: Boolean = true) =
        log(ActionCategory.TOOL_CALL, actor, tool, params, result, success)

    fun logDecision(actor: String, decision: String, rationale: String) =
        log(ActionCategory.DECISION, actor, decision, mapOf("rationale" to rationale), null)

    fun logPolicyCheck(actor: String, policy: String, allowed: Boolean) =
        log(ActionCategory.POLICY_CHECK, actor, policy, emptyMap(), if (allowed) "allowed" else "denied", allowed)

    fun logError(actor: String, error: String, context: Map<String, String> = emptyMap()) =
        log(ActionCategory.ERROR, actor, "error", context + mapOf("error" to error), null, false)

    fun log(
        category: ActionCategory,
        actor:    String,
        action:   String,
        params:   Map<String, String> = emptyMap(),
        result:   String?             = null,
        success:  Boolean             = true
    ): ActionLogEntry {
        val entry = ActionLogEntry(category = category, actor = actor, action = action,
            params = params, result = result, success = success)
        synchronized(actionLog) {
            actionLog.add(entry)
            if (actionLog.size > MAX_ACTION_LOG) actionLog.removeAt(0)
        }
        return entry
    }

    fun getActionLog(): List<ActionLogEntry> = synchronized(actionLog) { actionLog.toList() }

    fun getRecentActions(n: Int = 20): List<ActionLogEntry> =
        synchronized(actionLog) { actionLog.takeLast(n) }

    // ── Snapshot / rollback ───────────────────────────────────────────────────

    /**
     * Create a copy-on-write snapshot of the current file system state.
     * At most [MAX_SNAPSHOTS] snapshots are retained; oldest is evicted.
     *
     * Synchronized on [snapshots] to prevent concurrent snapshot + rollback
     * from corrupting the ArrayDeque (which is not thread-safe).
     */
    fun snapshot(): SandboxSnapshot {
        val snap = SandboxSnapshot(files = files.toMap(), logSize = actionLog.size)
        synchronized(snapshots) {
            if (snapshots.size >= MAX_SNAPSHOTS) snapshots.removeFirst()
            snapshots.addLast(snap)
        }
        log(ActionCategory.DECISION, "workspace", "snapshot", mapOf("snapId" to snap.id), "created")
        Log.i(TAG, "AIRI_PROOF WORKSPACE_SNAPSHOT goal=$goalId snapId=${snap.id} files=${snap.files.size}")
        return snap
    }

    /**
     * Roll back the file system to a previous snapshot. Action log is preserved.
     *
     * Atomicity guarantee: snapshot files are written FIRST (putAll), then keys
     * that do not belong to the snapshot are removed. This eliminates the
     * empty-files window that a clear() + putAll() sequence would create, so
     * concurrent readers always see at least the snapshot-level content.
     */
    fun rollback(snapId: String): Boolean {
        val snap = synchronized(snapshots) {
            snapshots.lastOrNull { it.id == snapId } ?: snapshots.lastOrNull()
        } ?: return false

        // Write-first, prune-second — no empty window visible to concurrent readers.
        files.putAll(snap.files)
        val snapKeys = snap.files.keys
        files.keys.retainAll(snapKeys)

        log(ActionCategory.DECISION, "workspace", "rollback", mapOf("snapId" to snap.id), "restored")
        Log.w(TAG, "AIRI_PROOF WORKSPACE_ROLLBACK goal=$goalId snapId=${snap.id} files=${snap.files.size}")
        return true
    }

    /** Roll back to the most recent snapshot (convenience for error recovery). */
    fun rollbackLatest(): Boolean {
        val latestId = synchronized(snapshots) { snapshots.lastOrNull()?.id } ?: return false
        return rollback(latestId)
    }

    fun listSnapshots(): List<SandboxSnapshot> = synchronized(snapshots) { snapshots.toList() }

    // ── Summary ───────────────────────────────────────────────────────────────

    fun summary(): WorkspaceSummary = WorkspaceSummary(
        goalId        = goalId,
        fileCount     = files.size,
        totalBytes    = files.values.sumOf { it.length },
        actionCount   = actionLog.size,
        snapshotCount = snapshots.size,
        fileList      = listFiles()
    )

    private fun normalisePath(path: String): String =
        "/" + path.trimStart('/').replace("\\", "/")
}

data class WorkspaceSummary(
    val goalId:        String,
    val fileCount:     Int,
    val totalBytes:    Int,
    val actionCount:   Int,
    val snapshotCount: Int,
    val fileList:      List<String>
)

