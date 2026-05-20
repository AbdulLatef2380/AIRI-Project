package com.airi.assistant.workspace

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkspaceRuntime — persistent workspace platform for AIRI.
 *
 * A "workspace" is a named project context that persists across conversations.
 * It owns:
 *  - A [SandboxManager] session for execution
 *  - An [ArtifactManager] collection for generated files
 *  - A working directory
 *  - Metadata (name, description, created/updated timestamps)
 *
 * Equivalent to a "project" in Replit or a "thread" in Claude Artifacts.
 */
class WorkspaceRuntime(
    private val context:         Context,
    private val sandboxManager:  SandboxManager,
    private val artifactManager: ArtifactManager
) {
    private val TAG = "WorkspaceRuntime"

    data class WorkspaceSession(
        val sessionId:   String = UUID.randomUUID().toString().take(8),
        val name:        String,
        val description: String = "",
        val createdAtMs: Long   = System.currentTimeMillis(),
        val updatedAtMs: Long   = System.currentTimeMillis(),
        val sandboxId:   String? = null,
        val isActive:    Boolean = true,
        val tags:        List<String> = emptyList()
    )

    private val sessions    = ConcurrentHashMap<String, WorkspaceSession>()
    private val _allSessions  = MutableStateFlow<List<WorkspaceSession>>(emptyList())
    val allSessions: StateFlow<List<WorkspaceSession>> = _allSessions.asStateFlow()

    private val _activeSession = MutableStateFlow<WorkspaceSession?>(null)
    val activeSession: StateFlow<WorkspaceSession?> = _activeSession.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Session lifecycle ─────────────────────────────────────────────────────

    fun createSession(name: String, description: String = ""): WorkspaceSession {
        val sandbox = sandboxManager.createSession("ws:$name")
        val session = WorkspaceSession(
            name        = name,
            description = description,
            sandboxId   = sandbox?.sessionId
        )
        sessions[session.sessionId] = session
        publishSessions()
        setActive(session.sessionId)
        AgentActivityBus.emit("Workspace created: $name", ActivityCategory.SANDBOX)
        Log.i(TAG, "Workspace session created: ${session.sessionId} name='$name'")
        return session
    }

    fun setActive(sessionId: String) {
        val session = sessions[sessionId] ?: return
        _activeSession.value = session.copy(updatedAtMs = System.currentTimeMillis())
        sessions[sessionId] = _activeSession.value!!
        publishSessions()
    }

    fun getSession(sessionId: String): WorkspaceSession? = sessions[sessionId]

    fun closeSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.sandboxId?.let { sandboxManager.closeSession(it) }
        sessions.remove(sessionId)
        if (_activeSession.value?.sessionId == sessionId) _activeSession.value = sessions.values.firstOrNull()
        publishSessions()
        AgentActivityBus.emit("Workspace closed: ${session.name}", ActivityCategory.SANDBOX)
    }

    fun closeAll() {
        sessions.keys.toList().forEach { closeSession(it) }
    }

    // ── Artifact helpers (convenience delegates) ──────────────────────────────

    suspend fun createArtifact(
        name:    String,
        type:    ArtifactManager.ArtifactType,
        content: String,
        desc:    String = ""
    ): ArtifactManager.Artifact? {
        val sessionId = _activeSession.value?.sessionId ?: return null
        return artifactManager.createArtifact(sessionId, name, type, content, desc)
    }

    fun artifactsForActive(): List<ArtifactManager.Artifact> =
        _activeSession.value?.let { artifactManager.forSession(it.sessionId) } ?: emptyList()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun publishSessions() {
        _allSessions.value = sessions.values.sortedByDescending { it.updatedAtMs }
    }
}
