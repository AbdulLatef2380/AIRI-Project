package com.airi.assistant.workspace

import android.content.Context
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class ArtifactManager(private val context: Context) {

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

    fun deleteArtifact(id: String) {
        val artifact = artifacts.remove(id) ?: return
        File(artifact.filePath).delete()
        publishAll()
    }

    fun deleteSession(sessionId: String) {
        val keys = artifacts.values.filter { it.sessionId == sessionId }.map { it.id }
        keys.forEach { deleteArtifact(it) }
        File(context.filesDir, "workspace/artifacts/$sessionId").deleteRecursively()
    }

    private fun publishAll() {
        _allArtifacts.value = artifacts.values.sortedByDescending { it.updatedAtMs }
    }
}
