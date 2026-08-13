package com.airi.assistant.runtime.recovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RuntimeRecoveryEngine — Phase R9 crash recovery and execution checkpointing.
 *
 * Provides durable checkpointing for all AIRI runtime subsystems so that
 * after a process death (OOM kill, forced crash, ANR kill) the runtime can
 * restore its critical state on next launch.
 *
 * ── Checkpoint categories ─────────────────────────────────────────────────
 *   ORCHESTRATION   — active task graph state and agent registry snapshot
 *   WORKSPACE       — current workspace artifact list + generation context
 *   VOICE           — whether a voice session was active (→ auto-restart)
 *   CONNECTOR       — connector auth states (so re-auth is minimal)
 *   TERMINAL        — last terminal command + working directory
 *   CONVERSATION    — chat session ID for conversation continuity
 *
 * ── Storage ───────────────────────────────────────────────────────────────
 * Checkpoints are written to files in Context.filesDir/checkpoints/ using
 * atomic rename (write to .tmp → rename to final) to prevent torn writes.
 *
 * ── Recovery protocol ─────────────────────────────────────────────────────
 * On Application.onCreate():
 *   1. RuntimeRecoveryEngine.init(context)
 *   2. Check hasCheckpoint(category) for each subsystem
 *   3. Call restoreCheckpoint(category) to get the saved JSON
 *   4. Each subsystem deserializes its own state
 *   5. Call clearCheckpoint(category) after successful restore
 */
class RuntimeRecoveryEngine(private val context: Context) {

    private val TAG = "RuntimeRecoveryEngine"

    enum class CheckpointCategory {
        ORCHESTRATION, WORKSPACE, VOICE, CONNECTOR, TERMINAL, CONVERSATION
    }

    data class RecoveryStatus(
        val availableCheckpoints: List<CheckpointCategory>,
        val lastCrashTimestampMs: Long?,
        val recoveryAttempts:     Int
    )

    private val checkpointDir = File(context.filesDir, "checkpoints").also { it.mkdirs() }
    private val inMemoryCache = ConcurrentHashMap<CheckpointCategory, String>()

    private val _status = MutableStateFlow<RecoveryStatus?>(null)
    val status: StateFlow<RecoveryStatus?> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun init() {
        val available = CheckpointCategory.values().filter { hasCheckpoint(it) }
        val crashTs   = readCrashTimestamp()
        val attempts  = readRecoveryAttempts()

        _status.value = RecoveryStatus(available, crashTs, attempts)

        if (available.isNotEmpty()) {
            Log.w(TAG, "AIRI_RUNTIME RECOVERY_AVAILABLE categories=$available lastCrash=$crashTs")
        } else {
            Log.i(TAG, "AIRI_RUNTIME NO_RECOVERY_NEEDED")
        }

        // Record this startup as a potential recovery attempt
        if (available.isNotEmpty()) writeRecoveryAttempts(attempts + 1)

        // Install crash handler to write timestamp on uncaught exception
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashTimestamp(System.currentTimeMillis()) }
            runCatching { existing?.uncaughtException(thread, throwable) }
        }
    }

    // ── Checkpoint API ─────────────────────────────────────────────────────

    fun saveCheckpoint(category: CheckpointCategory, data: JSONObject) {
        val json = data.toString()
        inMemoryCache[category] = json
        scope.launch {
            runCatching {
                val tmp   = File(checkpointDir, "${category.name}.tmp")
                val final = File(checkpointDir, "${category.name}.json")
                tmp.writeText(json)
                tmp.renameTo(final)
                Log.d(TAG, "AIRI_RUNTIME CHECKPOINT_SAVED category=$category size=${json.length}")
            }.onFailure { e ->
                Log.e(TAG, "AIRI_RUNTIME CHECKPOINT_WRITE_FAILED category=$category", e)
            }
        }
    }

    fun hasCheckpoint(category: CheckpointCategory): Boolean =
        inMemoryCache.containsKey(category) ||
                File(checkpointDir, "${category.name}.json").exists()

    fun restoreCheckpoint(category: CheckpointCategory): JSONObject? {
        return runCatching {
            val json = inMemoryCache[category]
                ?: File(checkpointDir, "${category.name}.json").readText()
            Log.i(TAG, "AIRI_RUNTIME CHECKPOINT_RESTORED category=$category")
            JSONObject(json)
        }.onFailure { e ->
            Log.e(TAG, "AIRI_RUNTIME CHECKPOINT_READ_FAILED category=$category", e)
        }.getOrNull()
    }

    fun clearCheckpoint(category: CheckpointCategory) {
        inMemoryCache.remove(category)
        File(checkpointDir, "${category.name}.json").delete()
        Log.d(TAG, "AIRI_RUNTIME CHECKPOINT_CLEARED category=$category")
    }

    fun clearAllCheckpoints() {
        CheckpointCategory.values().forEach { clearCheckpoint(it) }
        writeRecoveryAttempts(0)
        Log.i(TAG, "AIRI_RUNTIME ALL_CHECKPOINTS_CLEARED")
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun crashTsFile()     = File(checkpointDir, "last_crash.txt")
    private fun recoveryCtFile()  = File(checkpointDir, "recovery_count.txt")

    private fun writeCrashTimestamp(ms: Long) = crashTsFile().writeText(ms.toString())
    private fun readCrashTimestamp(): Long?   = crashTsFile().takeIf { it.exists() }
        ?.readText()?.toLongOrNull()

    private fun writeRecoveryAttempts(n: Int) = recoveryCtFile().writeText(n.toString())
    private fun readRecoveryAttempts(): Int    = recoveryCtFile().takeIf { it.exists() }
        ?.readText()?.toIntOrNull() ?: 0

    // ── Convenience builders ───────────────────────────────────────────────

    /** Snapshot helpers for each subsystem — call from respective managers. */

    fun checkpointConversation(sessionId: String, lastMessageIndex: Int) {
        saveCheckpoint(CheckpointCategory.CONVERSATION, JSONObject().apply {
            put("sessionId",        sessionId)
            put("lastMessageIndex", lastMessageIndex)
            put("ts",               System.currentTimeMillis())
        })
    }

    fun checkpointVoice(wasActive: Boolean) {
        saveCheckpoint(CheckpointCategory.VOICE, JSONObject().apply {
            put("wasActive", wasActive)
            put("ts",        System.currentTimeMillis())
        })
    }

    fun checkpointOrchestration(activeTaskIds: List<String>) {
        saveCheckpoint(CheckpointCategory.ORCHESTRATION, JSONObject().apply {
            put("activeTaskIds", org.json.JSONArray(activeTaskIds))
            put("ts",            System.currentTimeMillis())
        })
    }
}
