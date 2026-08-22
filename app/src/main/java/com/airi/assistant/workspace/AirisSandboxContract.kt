package com.airi.assistant.workspace

/**
 * Execution contract defining strict boundaries for AIRI Agent Sandbox.
 * Enforces filesystem, network, process, and secret limits per task.
 */
data class ExecutionContract(
    val taskId: String,
    val allowedPaths: List<String>,
    val allowNetwork: Boolean,
    val maxExecutionTimeMs: Long,
    val allowedCommandPrefixes: List<String>
)

object SandboxEnforcer {
    fun evaluateFilesystemAccess(contract: ExecutionContract, targetPath: String): Boolean {
        return contract.allowedPaths.any { targetPath.startsWith(it) }
    }

    fun evaluateCommandAccess(contract: ExecutionContract, command: String): Boolean {
        if (contract.allowedCommandPrefixes.isEmpty()) return false
        val trimmed = command.trim()
        return contract.allowedCommandPrefixes.any { trimmed.startsWith(it) }
    }
}
