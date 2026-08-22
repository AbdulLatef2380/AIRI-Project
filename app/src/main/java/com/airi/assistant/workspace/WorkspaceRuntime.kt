package com.airi.assistant.workspace

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.airi.assistant.agent.durable.DurableTaskManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the metadata and active selection for user workspaces.
 *
 * Workspace metadata is kept in app-private preferences. Generated artifacts are
 * stored separately by [ArtifactManager]. Sandbox sessions are process-scoped,
 * so a restored workspace deliberately has no sandbox ID until the user starts
 * a new sandbox operation for it.
 */
class WorkspaceRuntime(
    private val context: Context,
    private val sandboxManager: SandboxManager,
    private val artifactManager: ArtifactManager,
    private val durableTaskManager: DurableTaskManager? = null,
    private val projectFileManager: ProjectFileManager? = null
) {
    data class WorkspaceSession(
        val sessionId: String = UUID.randomUUID().toString().take(8),
        val name: String,
        val description: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
        val sandboxId: String? = null,
        val isActive: Boolean = true,
        val tags: List<String> = emptyList()
    )

    private val sessions = ConcurrentHashMap<String, WorkspaceSession>()
    private val _allSessions = MutableStateFlow<List<WorkspaceSession>>(emptyList())
    val allSessions: StateFlow<List<WorkspaceSession>> = _allSessions.asStateFlow()

    private val _activeSession = MutableStateFlow<WorkspaceSession?>(null)
    val activeSession: StateFlow<WorkspaceSession?> = _activeSession.asStateFlow()

    private val storage: SharedPreferences = context.getSharedPreferences(
        PREFS_FILE,
        Context.MODE_PRIVATE
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        restoreSessions()
        scope.launch {
            artifactManager.loadPersistedArtifacts()
        }
    }

    fun createSession(name: String, description: String = ""): WorkspaceSession {
        val sandbox = sandboxManager.createSession("ws:$name")
        val session = WorkspaceSession(
            name = name,
            description = description,
            sandboxId = sandbox?.sessionId
        )
        sessions[session.sessionId] = session
        setActive(session.sessionId)
        AgentActivityBus.emit("Workspace created", ActivityCategory.SANDBOX)
        Log.i(TAG, "Workspace created id=${session.sessionId} nameChars=${name.length}")
        return session
    }

    fun setActive(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val updated = session.copy(updatedAtMs = System.currentTimeMillis(), isActive = true)
        sessions[sessionId] = updated
        _activeSession.value = updated
        publishAndPersist()
    }

    fun getSession(sessionId: String): WorkspaceSession? = sessions[sessionId]

    fun closeSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.sandboxId?.let { sandboxManager.closeSession(it) }
        sessions.remove(sessionId)
        if (_activeSession.value?.sessionId == sessionId) {
            _activeSession.value = sessions.values.maxByOrNull { it.updatedAtMs }
        }
        publishAndPersist()
        AgentActivityBus.emit("Workspace closed", ActivityCategory.SANDBOX)
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::closeSession)
    }

    suspend fun createArtifact(
        name: String,
        type: ArtifactManager.ArtifactType,
        content: String,
        desc: String = ""
    ): ArtifactManager.Artifact? {
        val sessionId = _activeSession.value?.sessionId ?: return null
        return artifactManager.createArtifact(sessionId, name, type, content, desc)
    }

    fun artifactsForActive(): List<ArtifactManager.Artifact> =
        _activeSession.value?.let { artifactManager.forSession(it.sessionId) } ?: emptyList()

    /**
     * Returns the product-facing context for the selected workspace.
     * The context is derived from the existing session and artifact store.
     */
    fun contextForActive(): WorkspaceContext? {
        val session = _activeSession.value ?: return null
        return workspaceContextFrom(
            session = session,
            artifacts = artifactManager.forSession(session.sessionId),
            tasks = durableTaskManager?.tasks?.value.orEmpty(),
            projectFiles = projectFileManager?.files?.value.orEmpty()
        )
    }

    private fun restoreSessions() {
        val serialized = storage.getString(KEY_SESSIONS, null) ?: return
        runCatching {
            val values = JSONArray(serialized)
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val sessionId = value.optString(JSON_SESSION_ID)
                val name = value.optString(JSON_NAME)
                if (sessionId.isBlank() || name.isBlank()) continue
                val tags = value.optJSONArray(JSON_TAGS)?.let { array ->
                    buildList {
                        for (tagIndex in 0 until array.length()) {
                            array.optString(tagIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.orEmpty()
                sessions[sessionId] = WorkspaceSession(
                    sessionId = sessionId,
                    name = name,
                    description = value.optString(JSON_DESCRIPTION),
                    createdAtMs = value.optLong(JSON_CREATED_AT, System.currentTimeMillis()),
                    updatedAtMs = value.optLong(JSON_UPDATED_AT, System.currentTimeMillis()),
                    sandboxId = null,
                    isActive = true,
                    tags = tags
                )
            }
            val activeId = storage.getString(KEY_ACTIVE_SESSION, null)
            _activeSession.value = activeId?.let(sessions::get)
                ?: sessions.values.maxByOrNull { it.updatedAtMs }
            publishSessions()
            Log.i(TAG, "Restored workspaceCount=${sessions.size}")
        }.onFailure { error ->
            sessions.clear()
            _activeSession.value = null
            publishSessions()
            Log.w(TAG, "Workspace restore failed type=${error.javaClass.simpleName}")
        }
    }

    private fun publishAndPersist() {
        publishSessions()
        val values = JSONArray()
        sessions.values.sortedByDescending { it.updatedAtMs }.forEach { session ->
            values.put(JSONObject().apply {
                put(JSON_SESSION_ID, session.sessionId)
                put(JSON_NAME, session.name)
                put(JSON_DESCRIPTION, session.description)
                put(JSON_CREATED_AT, session.createdAtMs)
                put(JSON_UPDATED_AT, session.updatedAtMs)
                put(JSON_TAGS, JSONArray(session.tags))
            })
        }
        storage.edit()
            .putString(KEY_SESSIONS, values.toString())
            .putString(KEY_ACTIVE_SESSION, _activeSession.value?.sessionId)
            .apply()
    }

    private fun publishSessions() {
        _allSessions.value = sessions.values.sortedByDescending { it.updatedAtMs }
    }

    private companion object {
        const val TAG = "WorkspaceRuntime"
        const val PREFS_FILE = "airi_workspace_sessions"
        const val KEY_SESSIONS = "sessions"
        const val KEY_ACTIVE_SESSION = "active_session"
        const val JSON_SESSION_ID = "id"
        const val JSON_NAME = "name"
        const val JSON_DESCRIPTION = "description"
        const val JSON_CREATED_AT = "createdAtMs"
        const val JSON_UPDATED_AT = "updatedAtMs"
        const val JSON_TAGS = "tags"
    }
}
