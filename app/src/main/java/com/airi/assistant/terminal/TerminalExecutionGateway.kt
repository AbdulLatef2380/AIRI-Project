package com.airi.assistant.terminal

import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.agent.sandbox.SandboxSession
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
    suspend fun executeForAgent(command: String, agentId: String): Result {
        val session = createSession("agent:$agentId")
            ?: return Result.Denied("Could not allocate an agent workspace")
        return try {
            execute(session, command, agentId)
        } finally {
            closeSession(session.sessionId)
        }
    }

    suspend fun execute(
        session: SandboxSession,
        command: String,
        agentId: String = "terminal"
    ): Result {
        val policy = TerminalExecutionPolicy.evaluate(command)
        if (!policy.allowed) return Result.Disabled(policy.reason, policy.requiresApproval)

        val decision = runCatching {
            governance.evaluate(
                actionType = "terminal_execute",
                actionDesc = CommandRedactor.redact(command),
                agentId = agentId,
                payload = command
            )
        }.getOrElse { return Result.Denied("Terminal governance failed closed") }
        if (!decision.allowed) return Result.Denied(decision.reason)

        return Result.Completed(
            SandboxExecutor(session).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.SHELL_COMMAND,
                    command = command
                )
            )
        )
    }
}
