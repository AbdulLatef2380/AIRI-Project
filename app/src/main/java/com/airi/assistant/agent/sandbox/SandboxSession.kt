package com.airi.assistant.agent.sandbox

import java.io.File
import java.util.UUID

data class SandboxSession(
    val sessionId: String,
    val label: String,
    val workspaceDir: File,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    private val _execLog = mutableListOf<SandboxLogEntry>()
    @Volatile private var activeExecutions = 0
    val execLog: List<SandboxLogEntry> get() = synchronized(_execLog) { _execLog.toList() }
    fun appendLog(entry: SandboxLogEntry) { synchronized(_execLog) { _execLog.add(entry) } }
    val isAlive: Boolean get() = workspaceDir.exists() && workspaceDir.isDirectory
    val isInUse: Boolean get() = synchronized(this) { activeExecutions > 0 }
    @Synchronized fun beginUse(): Boolean {
        if (!isAlive) return false
        activeExecutions += 1
        return true
    }
    @Synchronized fun endUse() { if (activeExecutions > 0) activeExecutions -= 1 }
    @Synchronized fun release() { if (activeExecutions == 0) workspaceDir.deleteRecursively() }
    companion object { fun newId() = "sbx_${UUID.randomUUID().toString().take(8)}" }
}

data class SandboxLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val message: String
)
