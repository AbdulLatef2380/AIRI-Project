package com.airi.assistant.terminal

import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.agent.sandbox.SandboxSession
import com.airi.assistant.agent.sandbox.ShellExecutionPermit
import com.airi.assistant.domain.terminal.TerminalExecutionPolicy
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.security.PermissionGovernanceLayer

/**
 * Single coordinator for every terminal-like surface.
 *
 * No UI or agent may instantiate SandboxExecutor directly. The gateway owns the
 * policy decision, governance check, session lookup and eventual dispatch.
 * Agent calls receive a fresh, bounded workspace session. UI sessions still
 * use the same gateway and never instantiate the executor directly.
 */
class TerminalExecutionGateway(
    private val sandboxManager: SandboxManager,
    private val governance: PermissionGovernanceLayer
) {
    sealed class Result {
        data class Disabled(val reason: String, val requiresApproval: Boolean = false) : Result()
        data class Denied(val reason: String) : Result()
        data class Completed(val result: SandboxExecutor.ExecutionResult) : Result()
    }

    fun createSession(label: String): SandboxSession? = sandboxManager.createSession(label)
    fun getSession(id: String): SandboxSession? = sandboxManager.getSession(id)
    fun closeSession(id: String) = sandboxManager.closeSession(id)

    /** Execute one agent request in a task-owned ephemeral workspace. */
    suspend fun executeForAgent(request: TerminalExecutionRequest, seedFilesFrom: java.io.File? = null): Result {
        if (!request.isStructurallyValid()) {
            return Result.Denied("Terminal request has incomplete or invalid execution correlation")
        }
        val session = createSession("agent:${request.context.agentId}:${request.context.taskId}")
            ?: return Result.Denied("Could not allocate an agent workspace")
        return try {
            // Report د-2: files saved by write_file live in another folder. The task workspace gets
            // a bounded COPY of them, so `cat`/`grep` can read what the agent wrote. The copy is
            // thrown away with the workspace; the originals are never exposed to the command.
            seedFilesFrom?.let { copySeedFiles(it, session.workspaceDir) }
            execute(session, request)
        } finally {
            closeSession(session.sessionId)
        }
    }

    private fun copySeedFiles(source: java.io.File, target: java.io.File) {
        runCatching {
            if (!source.isDirectory) return
            source.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.length() <= SEED_MAX_FILE_BYTES }
                .orEmpty()
                .sortedBy { it.name }
                .take(SEED_MAX_FILES)
                .forEach { it.copyTo(java.io.File(target, it.name), overwrite = true) }
        }
    }

    private companion object {
        const val SEED_MAX_FILES = 50
        const val SEED_MAX_FILE_BYTES = 256L * 1024
    }

    suspend fun execute(
        session: SandboxSession,
        request: TerminalExecutionRequest
    ): Result {
        if (!request.isStructurallyValid()) {
            return Result.Denied("Terminal request has incomplete or invalid execution correlation")
        }
        if (!sandboxManager.ownsSession(session)) {
            return Result.Denied("Sandbox session is not owned by the active sandbox manager")
        }
        if (!session.beginUse()) {
            return Result.Denied("Sandbox session is unavailable")
        }
        try {
            val policy = TerminalExecutionPolicy.evaluate(request.command)
            if (!policy.allowed) return Result.Disabled(policy.reason, policy.requiresApproval)

            val decision = runCatching {
                governance.evaluate(
                    actionType = "terminal_execute",
                    actionDesc = "${CommandRedactor.redact(request.command)} [${request.correlationLabel()}]",
                    agentId = request.context.agentId,
                    // Governance receives only the redacted representation;
                    // the raw command remains inside the bounded executor and
                    // is never copied into audit/policy payloads.
                    payload = "${CommandRedactor.redact(request.command)} [${request.correlationLabel()}]"
                )
            }.getOrElse { return Result.Denied("Terminal governance failed closed") }
            if (!decision.allowed) return Result.Denied(decision.reason)

            return Result.Completed(
                SandboxExecutor(session).execute(
                    SandboxExecutor.SandboxTask(
                        type = SandboxExecutor.TaskType.SHELL_COMMAND,
                        command = request.command
                    ),
                    permit = ShellExecutionPermit.gateway()
                )
            )
        } finally {
            session.endUse()
        }
    }
}
