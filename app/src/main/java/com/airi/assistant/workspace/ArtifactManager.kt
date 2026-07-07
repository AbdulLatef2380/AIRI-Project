package com.airi.assistant.workspace

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.dao.ArtifactDao
import com.airi.assistant.memory.entity.ArtifactEntity
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ArtifactManager — manages generated files, code outputs, and workspace artifacts.
 *
 * Artifacts are stored under `<filesDir>/workspace/artifacts/<sessionId>/`.
 * Each artifact has a type, version history, and preview capability.
 *
 * Integrates with:
 *  - [SandboxManager] for sandbox-generated files
 *  - [AgentActivityBus] for user-visible creation events
 *  - [WorkspacePersistenceManager] for session persistence
 */
/**
 * T22: Accepts an optional [ArtifactDao] so every create/update/delete is
 * also persisted to Room — surviving process death. Call [loadPersistedArtifacts]
 * once from a coroutine on startup to restore previously-saved artifacts.
 */
class ArtifactManager(
    private val context: Context,
    private val artifactDao: ArtifactDao? = null
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val TAG = "ArtifactManager"

    enum class ArtifactType(val ext: String, val emoji: String) {
        CODE_KOTLIN   ("kt",   "🟣"),
        CODE_PYTHON   ("py",   "🐍"),
        CODE_HTML     ("html", "🌐"),
        CODE_JS       ("js",   "📜"),
        MARKDOWN      ("md",   "📝"),
        TEXT          ("txt",  "📄"),
        JSON          ("json", "📊"),
        SHELL_SCRIPT  ("sh",   "🔧"),
        WEBSITE       ("html", "🌐"),
        PRESENTATION  ("md",   "📊"),
        REPORT        ("md",   "📋"),
        AUTOMATION    ("json", "🤖"),
        DIAGRAM       ("mmd",  "🗂"),
        UNKNOWN       ("bin",  "📦")
    }

    data class Artifact(
        val id:          String = UUID.randomUUID().toString().take(8),
        val sessionId:   String,
        val name:        String,
        val type:        ArtifactType,
        val filePath:    String,
        val sizeBytes:   Long = 0L,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
        val version:     Int  = 1,
        val description: String = "",
        val agentId:     String = "",
        /** Non-null if a preview snapshot exists (e.g. first 512 chars). */
        val previewSnippet: String? = null
    )

    private val artifacts   = ConcurrentHashMap<String, Artifact>()
    private val _allArtifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val allArtifacts: StateFlow<List<Artifact>> = _allArtifacts.asStateFlow()

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun createArtifact(
        sessionId:   String,
        name:        String,
        type:        ArtifactType,
        content:     String,
        description: String = "",
        agentId:     String = ""
    ): Artifact = withContext(Dispatchers.IO) {
        val id      = UUID.randomUUID().toString().take(8)
        val dir     = File(context.filesDir, "workspace/artifacts/$sessionId").also { it.mkdirs() }
        val file    = File(dir, "$name.${type.ext}")
        file.writeText(content, Charsets.UTF_8)

        val artifact = Artifact(
            id             = id,
            sessionId      = sessionId,
            name           = name,
            type           = type,
            filePath       = file.absolutePath,
            sizeBytes      = file.length(),
            description    = description,
            agentId        = agentId,
            previewSnippet = content.take(512)
        )
        artifacts[id] = artifact
        publishAll()
        artifactDao?.insert(artifact.toEntity())
        AgentActivityBus.emit(
            "${type.emoji} Artifact created: $name (${file.length() / 1024}KB)",
            ActivityCategory.SANDBOX
        )
        Log.i(TAG, "Artifact created: $name at ${file.absolutePath}")
        artifact
    }

    suspend fun updateArtifact(id: String, newContent: String): Artifact? = withContext(Dispatchers.IO) {
        val existing = artifacts[id] ?: return@withContext null
        val file = File(existing.filePath)
        file.writeText(newContent, Charsets.UTF_8)
        val updated = existing.copy(
            sizeBytes      = file.length(),
            updatedAtMs    = System.currentTimeMillis(),
            version        = existing.version + 1,
            previewSnippet = newContent.take(512)
        )
        artifacts[id] = updated
        publishAll()
        artifactDao?.update(updated.toEntity())
        updated
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getArtifact(id: String): Artifact? = artifacts[id]

    fun forSession(sessionId: String): List<Artifact> =
        artifacts.values.filter { it.sessionId == sessionId }.sortedByDescending { it.updatedAtMs }

    suspend fun readContent(id: String): String? = withContext(Dispatchers.IO) {
        val artifact = artifacts[id] ?: return@withContext null
        runCatching { File(artifact.filePath).readText(Charsets.UTF_8) }.getOrNull()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Wipe ALL artifacts across every session — in-memory state and disk files.
     *
     * Called exclusively by [com.airi.assistant.domain.auth.DataDeletionCoordinator]
     * during GDPR account deletion (Step 4 — FILESYSTEM_WIPE).
     *
     * ── Scope ──────────────────────────────────────────────────────────────────
     * This method handles two of the three artifact data layers:
     *   Layer 1 (in-memory) — [artifacts] ConcurrentHashMap is cleared and the
     *     [allArtifacts] StateFlow is updated to an empty list.
     *   Layer 2 (disk)      — the entire <filesDir>/workspace/artifacts/ directory
     *     is deleted recursively, removing all session subdirectories and files.
     *
     * Layer 3 (Room) is intentionally excluded: the Room [workspace_artifact]
     * table is wiped atomically in the same deletion workflow by
     * [com.airi.assistant.memory.repository.StorageRepository.deleteAllData]
     * (Step 3 — ROOM_DATA_WIPE). Duplicating the Room wipe here would mean the
     * DAO call runs outside the cross-table transaction. The coordinator's step
     * ordering guarantees the Room wipe completes before this method runs.
     *
     * ── Idempotency ────────────────────────────────────────────────────────────
     * Safe to call on an already-empty manager: clearing an empty map and
     * calling [File.deleteRecursively] on a nonexistent directory are both no-ops.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        artifacts.clear()
        publishAll()
        val artifactsRoot = File(context.filesDir, "workspace/artifacts")
        val deleted = artifactsRoot.deleteRecursively()
        Log.i(TAG, "AIRI_PROOF GDPR_ARTIFACT_WIPE_COMPLETE deleted=$deleted path=${artifactsRoot.absolutePath}")
    }

    fun deleteArtifact(id: String) {
        val artifact = artifacts.remove(id) ?: return
        File(artifact.filePath).delete()
        publishAll()
        ioScope.launch { artifactDao?.deleteById(id) }
    }

    fun deleteSession(sessionId: String) {
        val keys = artifacts.values.filter { it.sessionId == sessionId }.map { it.id }
        keys.forEach { deleteArtifact(it) }
        File(context.filesDir, "workspace/artifacts/$sessionId").deleteRecursively()
        ioScope.launch { artifactDao?.deleteForSession(sessionId) }
    }

    /**
     * T22: Restore previously-persisted artifacts from Room on startup.
     * Call once from a coroutine after the ViewModel or Application initializes.
     * Entities whose backing files no longer exist are silently skipped.
     */
    suspend fun loadPersistedArtifacts() = withContext(Dispatchers.IO) {
        val dao = artifactDao ?: return@withContext
        runCatching {
            dao.getAll().forEach { entity ->
                if (File(entity.filePath).exists()) {
                    val artifact = entity.toArtifact()
                    artifacts[artifact.id] = artifact
                }
            }
            publishAll()
            Log.i(TAG, "Restored ${artifacts.size} artifact(s) from Room")
        }.onFailure { e -> Log.w(TAG, "loadPersistedArtifacts failed: ${e.message}") }
    }

    private fun publishAll() {
        _allArtifacts.value = artifacts.values.sortedByDescending { it.updatedAtMs }
    }

    // ── Entity mapping helpers ─────────────────────────────────────────────────

    private fun Artifact.toEntity() = ArtifactEntity(
        id             = id,
        sessionId      = sessionId,
        name           = name,
        typeName       = type.name,
        filePath       = filePath,
        sizeBytes      = sizeBytes,
        createdAtMs    = createdAtMs,
        updatedAtMs    = updatedAtMs,
        version        = version,
        description    = description,
        agentId        = agentId,
        previewSnippet = previewSnippet
    )

    private fun ArtifactEntity.toArtifact() = Artifact(
        id             = id,
        sessionId      = sessionId,
        name           = name,
        type           = runCatching { ArtifactType.valueOf(typeName) }.getOrDefault(ArtifactType.UNKNOWN),
        filePath       = filePath,
        sizeBytes      = sizeBytes,
        createdAtMs    = createdAtMs,
        updatedAtMs    = updatedAtMs,
        version        = version,
        description    = description,
        agentId        = agentId,
        previewSnippet = previewSnippet
    )
}
