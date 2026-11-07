package com.airi.assistant.agent.sandbox

import android.content.Context
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SandboxManager(private val context: Context) {
    private val TAG = "SandboxManager"
    private companion object { const val MAX_SESSIONS = 5 }
    private val sessions = ConcurrentHashMap<String, SandboxSession>()
    private val sessionLock = Any()
    private val _activeSessions = MutableStateFlow<List<SandboxSession>>(emptyList())
    val activeSessions: StateFlow<List<SandboxSession>> = _activeSessions.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init { startReaper() }

    fun createSession(label: String): SandboxSession? {
        // Never evict an existing session implicitly: it may own a running
        // process or uncommitted user work. Callers must close one explicitly.
        val session = synchronized(sessionLock) {
            if (sessions.size >= MAX_SESSIONS) {
                Log.w(TAG, "Session allocation denied: maximum active sessions reached")
                return null
            }
            val id = SandboxSession.newId()
            val dir = File(context.filesDir, "sandbox/$id").also { it.mkdirs() }
            if (!dir.exists()) {
                Log.e(TAG, "Failed to create dir for $id")
                return null
            }
            SandboxSession(id, label, dir).also { sessions[it.sessionId] = it; publishSessions() }
        }
        AgentActivityBus.emit("Sandbox session created: $label", ActivityCategory.SANDBOX)
        return session
    }

    private suspend fun execute(label: String, task: SandboxExecutor.SandboxTask): SandboxExecutor.ExecutionResult {
        val session = createSession(label) ?: return SandboxExecutor.ExecutionResult.Failure("Could not allocate session")
        AgentActivityBus.emit("Sandbox task: ${task.type} — ${task.command.take(50)}", ActivityCategory.SANDBOX)
        if (!session.beginUse()) return SandboxExecutor.ExecutionResult.Failure("Sandbox session is unavailable")
        return try {
            SandboxExecutor(session).execute(task).also {
                AgentActivityBus.emit("Sandbox complete: ${it::class.simpleName}", ActivityCategory.SANDBOX)
            }
        } finally {
            session.endUse()
            closeSession(session.sessionId)
        }
    }

    fun getSession(id: String): SandboxSession? = sessions[id]

    /** A gateway session must be the live object allocated by this manager. */
    fun ownsSession(session: SandboxSession): Boolean =
        sessions[session.sessionId] === session && session.isAlive

    fun closeSession(id: String) {
        synchronized(sessionLock) {
            val session = sessions[id] ?: return
            if (session.isInUse) return
            sessions.remove(id)?.release()
            publishSessions()
        }
        AgentActivityBus.emit("Sandbox session closed: $id", ActivityCategory.SANDBOX)
    }

    fun closeAll() {
        sessions.keys.toList().forEach { closeSession(it) }
        if (sessions.isEmpty()) File(context.filesDir, "sandbox").deleteRecursively()
    }

    private fun startReaper() {
        scope.launch { while (true) { delay(300_000L)
            sessions.values.filter { !it.isInUse && System.currentTimeMillis() - it.createdAtMs > 1_800_000L }
                .forEach { closeSession(it.sessionId) } } }
    }
    private fun publishSessions() { _activeSessions.value = sessions.values.sortedByDescending { it.createdAtMs } }
}
