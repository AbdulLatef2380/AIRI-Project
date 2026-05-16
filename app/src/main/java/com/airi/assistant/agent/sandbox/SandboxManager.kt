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
    private val sessions = ConcurrentHashMap<String, SandboxSession>()
    private val _activeSessions = MutableStateFlow<List<SandboxSession>>(emptyList())
    val activeSessions: StateFlow<List<SandboxSession>> = _activeSessions.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init { startReaper() }

    fun createSession(label: String): SandboxSession? {
        if (sessions.size >= 5) evictOldest()
        val id = SandboxSession.newId()
        val dir = File(context.filesDir, "sandbox/$id").also { it.mkdirs() }
        if (!dir.exists()) { Log.e(TAG, "Failed to create dir for $id"); return null }
        val session = SandboxSession(id, label, dir)
        sessions[id] = session; publishSessions()
        AgentActivityBus.emit("Sandbox session created: $label", ActivityCategory.SANDBOX)
        return session
    }

    suspend fun execute(label: String, task: SandboxExecutor.SandboxTask): SandboxExecutor.ExecutionResult {
        val session = createSession(label) ?: return SandboxExecutor.ExecutionResult.Failure("Could not allocate session")
        AgentActivityBus.emit("Sandbox task: ${task.type} — ${task.command.take(50)}", ActivityCategory.SANDBOX)
        val result = SandboxExecutor(session).execute(task)
        AgentActivityBus.emit("Sandbox complete: $result", ActivityCategory.SANDBOX)
        return result
    }

    fun getSession(id: String): SandboxSession? = sessions[id]

    fun closeSession(id: String) {
        sessions.remove(id)?.release(); publishSessions()
        AgentActivityBus.emit("Sandbox session closed: $id", ActivityCategory.SANDBOX)
    }

    fun closeAll() { sessions.keys.toList().forEach { closeSession(it) }; File(context.filesDir, "sandbox").deleteRecursively() }

    private fun evictOldest() { sessions.values.minByOrNull { it.createdAtMs }?.let { closeSession(it.sessionId) } }
    private fun startReaper() {
        scope.launch { while (true) { delay(300_000L)
            sessions.values.filter { System.currentTimeMillis() - it.createdAtMs > 1_800_000L }
                .forEach { closeSession(it.sessionId) } } }
    }
    private fun publishSessions() { _activeSessions.value = sessions.values.sortedByDescending { it.createdAtMs } }
}
