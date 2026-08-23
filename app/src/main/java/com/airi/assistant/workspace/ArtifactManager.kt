package com.airi.assistant.workspace

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.dao.ArtifactDao
import com.airi.assistant.memory.entity.ArtifactEntity
import com.airi.assistant.agent.durable.DurableTaskManager
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
import java.security.MessageDigest
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
    private val artifactDao: ArtifactDao? = null,
    private val durableTaskManager: DurableTaskManager? = null
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val TAG = "ArtifactManager"

    enum class ArtifactType(val ext: String, val emoji: String) {
        CODE_KOTLIN   ("kt",   ""),
        CODE_PYTHON   ("py",   ""),
        CODE_HTML     ("html", ""),
        CODE_JS       ("js",   ""),
        MARKDOWN      ("md",   ""),
        TEXT          ("txt",  ""),
        JSON          ("json", ""),
        SHELL_SCRIPT  ("sh",   ""),
        WEBSITE       ("html", ""),
        PRESENTATION  ("md",   ""),
        REPORT        ("md",   ""),
        AUTOMATION    ("json", ""),
        DIAGRAM       ("mmd",  ""),
        UNKNOWN       ("bin",  "")
    }

    data class ArtifactRevision(
        val artifactId: String,
        val version: Int,
        val filePath: String,
        val sizeBytes: Long,
        val capturedAtMs: Long
    )

    data class Artifact(
        val id:          String = UUID.randomUUID().toString().take(8),
        val sessionId:   String,
        /** Project owner. Legacy records normalize to their session ID. */
        val projectId:   String = sessionId,
        /** Optional durable execution owner; task implies matching run and step. */
        val taskId:      String? = null,
        val runId:       String? = null,
        val stepId:      String? = null,
        /** Bounded identifiers, not raw tool/model requests or provider payloads. */
        val toolId:      String? = null,
        val modelId:     String? = null,
        val provenanceSummary: String = "",
        /** SHA-256 of written content, used only for integrity/evidence comparison. */
        val contentHash: String = "",
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
        agentId:     String = "",
        provenance: ArtifactProvenance = ArtifactProvenance(projectId = sessionId)
    ): Artifact = withContext(Dispatchers.IO) {
        val validatedProvenance = validateProvenance(sessionId, provenance)
        val id      = UUID.randomUUID().toString().take(8)
        val dir     = File(context.filesDir, "workspace/artifacts/$sessionId").also { it.mkdirs() }
        val safeName = safeArtifactName(name)
        val file    = File(dir, "$id-$safeName.${type.ext}")
        file.writeText(content, Charsets.UTF_8)

        val artifact = Artifact(
            id             = id,
            sessionId      = sessionId,
            projectId      = validatedProvenance.projectId,
            taskId         = validatedProvenance.taskId,
            runId          = validatedProvenance.runId,
            stepId         = validatedProvenance.stepId,
            toolId         = validatedProvenance.toolId,
            modelId        = validatedProvenance.modelId,
            provenanceSummary = validatedProvenance.summary,
            contentHash    = sha256(content),
            name           = safeName,
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
        if (!file.exists()) return@withContext null
        val history = historyFile(existing, existing.version).also { it.parentFile?.mkdirs() }
        file.copyTo(history, overwrite = true)
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

    /** Project-scoped read boundary. Callers must not use an ID alone across projects. */
    fun forProject(projectId: String): List<Artifact> =
        artifacts.values.filter { it.projectId == projectId }.sortedByDescending { it.updatedAtMs }

    fun getArtifactForProject(id: String, projectId: String): Artifact? =
        artifacts[id]?.takeIf { it.projectId == projectId }

    suspend fun readContentForProject(id: String, projectId: String): String? = withContext(Dispatchers.IO) {
        val artifact = getArtifactForProject(id, projectId) ?: return@withContext null
        runCatching { File(artifact.filePath).readText(Charsets.UTF_8) }.getOrNull()
    }

    suspend fun readContent(id: String): String? = withContext(Dispatchers.IO) {
        val artifact = artifacts[id] ?: return@withContext null
        runCatching { File(artifact.filePath).readText(Charsets.UTF_8) }.getOrNull()
    }

    fun listVersions(id: String): List<ArtifactRevision> {
        val artifact = artifacts[id] ?: return emptyList()
        val directory = historyDirectory(artifact)
        val archived = directory.listFiles()
            ?.mapNotNull { file ->
                VERSION_PATTERN.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { version ->
                    ArtifactRevision(id, version, file.absolutePath, file.length(), file.lastModified())
                }
            }
            .orEmpty()
        val current = ArtifactRevision(id, artifact.version, artifact.filePath, artifact.sizeBytes, artifact.updatedAtMs)
        return (archived + current).distinctBy { it.version }.sortedByDescending { it.version }
    }

    suspend fun restoreVersion(id: String, version: Int): Artifact? = withContext(Dispatchers.IO) {
        val artifact = artifacts[id] ?: return@withContext null
        if (version == artifact.version) return@withContext artifact
        val snapshot = historyFile(artifact, version)
        if (!snapshot.exists()) return@withContext null
        val restoredContent = runCatching { snapshot.readText(Charsets.UTF_8) }.getOrNull()
            ?: return@withContext null
        updateArtifact(id, restoredContent)
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
        Log.i(TAG, "AIRI GDPR_ARTIFACT_WIPE_COMPLETE deleted=$deleted path=${artifactsRoot.absolutePath}")
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

    /**
     * Enforces project and durable task ownership before any artifact file is
     * written. Unscoped legacy artifacts remain supported only when their
     * session/project identity is identical.
     */
    private fun validateProvenance(sessionId: String, provenance: ArtifactProvenance): ArtifactProvenance {
        require(provenance.isWellFormed()) { "Artifact provenance is malformed or contains sensitive metadata" }
        require(provenance.projectId == sessionId) { "Artifact project must match its active workspace session" }
        val taskId = provenance.taskId ?: return provenance
        val task = durableTaskManager?.getTask(taskId)
            ?: throw IllegalArgumentException("Task-owned artifact requires an active durable task")
        require(task.projectId == provenance.projectId) { "Artifact task does not belong to project" }
        require(task.currentRunId == provenance.runId) { "Artifact run does not match active task run" }
        require(
            task.plan.any { step ->
                step.id == provenance.stepId &&
                    step.runId == provenance.runId &&
                    step.status == com.airi.assistant.agent.durable.TaskStepStatus.RUNNING
            }
        ) { "Artifact step does not match an active task step" }
        return provenance
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun historyDirectory(artifact: Artifact): File =
        File(File(artifact.filePath).parentFile, ".history/${artifact.id}")

    private fun historyFile(artifact: Artifact, version: Int): File =
        File(historyDirectory(artifact), "version-$version.${artifact.type.ext}")

    private fun safeArtifactName(value: String): String {
        val normalized = value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_', '-')
            .take(MAX_ARTIFACT_NAME_CHARS)
        return normalized.ifBlank { "artifact" }
    }

    private fun publishAll() {
        _allArtifacts.value = artifacts.values.sortedByDescending { it.updatedAtMs }
    }

    // ── Entity mapping helpers ─────────────────────────────────────────────────

    private fun Artifact.toEntity() = ArtifactEntity(
        id             = id,
        sessionId      = sessionId,
        projectId      = projectId,
        taskId         = taskId,
        runId          = runId,
        stepId         = stepId,
        toolId         = toolId,
        modelId        = modelId,
        provenanceSummary = provenanceSummary,
        contentHash    = contentHash,
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
        projectId      = projectId.ifBlank { sessionId },
        taskId         = taskId,
        runId          = runId,
        stepId         = stepId,
        toolId         = toolId,
        modelId        = modelId,
        provenanceSummary = provenanceSummary,
        contentHash    = contentHash,
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

    private companion object {
        const val MAX_ARTIFACT_NAME_CHARS = 80
        val VERSION_PATTERN = Regex("version-(\\d+)\\.[A-Za-z0-9]+")
    }
}
