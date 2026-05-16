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
    val execLog: List<SandboxLogEntry> get() = synchronized(_execLog) { _execLog.toList() }
    fun appendLog(entry: SandboxLogEntry) { synchronized(_execLog) { _execLog.add(entry) } }
    val isAlive: Boolean get() = workspaceDir.exists() && workspaceDir.isDirectory
    fun release() { workspaceDir.deleteRecursively() }
    companion object { fun newId() = "sbx_${UUID.randomUUID().toString().take(8)}" }
}

data class SandboxLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val message: String
)
