package com.airi.assistant.security

import android.util.Log
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.workspace.ActionCategory
import com.airi.assistant.agent.workspace.SandboxWorkspace
import com.airi.assistant.agent.workspace.WorkspaceRegistry
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.telemetry.AgentTelemetryEvent
import com.airi.assistant.telemetry.PrivacyTelemetryReporter

/**
 * AgentSandbox — permission-checked, resource-tracked, workspace-aware sandbox.
 *
 * ── WHAT IT DOES ──────────────────────────────────────────────────────────
 *
 *   1. Verifies the agent holds all declared required permissions.
 *   2. Injects a [SandboxContext] so the agent can query its own grants,
 *      guard tool calls, AND access a [SandboxWorkspace] (virtual FS).
 *   3. Takes a workspace snapshot BEFORE execution begins so the workspace
 *      can be rolled back on [SandboxViolationException].
 *   4. Catches security violations, emits telemetry, rolls back workspace,
 *      and re-throws as [SandboxViolationException].
 *   5. Logs every entry/exit with AIRI tag for audit.
 *
 * ── WORKSPACE ─────────────────────────────────────────────────────────────
 *
 *   Every sandboxed execution receives a per-goal [SandboxWorkspace] from
 *   [WorkspaceRegistry].  The agent body can:
 *     ctx.workspace.writeFile("/output/result.txt", "…")
 *     ctx.workspace.readFile("/output/result.txt")
 *     ctx.workspace.listFiles()
 *     ctx.workspace.rollbackLatest()   // explicit rollback
 *
 *   On security violation the sandbox rolls the workspace back automatically.
 *
 * ── USAGE ─────────────────────────────────────────────────────────────────
 *
 *     val result = sandbox.execute(agentId, goalId, requiredPermissions) { ctx ->
 *         ctx.workspace.writeFile("/draft.md", "# Hello")
 *         agent.run(input, ctx)
 *     }
 */
class AgentSandbox(
    private val firewall: ExecutionFirewall,
    private val registry: ScopedPermissionRegistry,
    private val telemetry: PrivacyTelemetryReporter
) {

    private val TAG = "AgentSandbox"

    // ── Primary execute overload (with workspace) ─────────────────────────────

    /**
     * Execute [block] inside a sandboxed context for [agentId] / [goalId].
     *
     * @param agentId              The agent whose grants are checked.
     * @param goalId               Identifies the workspace (created on first use).
     * @param requiredPermissions  All must be granted or execution is denied.
     * @param block                Receives a [SandboxContext] with workspace access.
     */
    suspend fun <T> execute(
        agentId:             String,
        goalId:              String                                     = agentId,
        requiredPermissions: Set<ScopedPermissionRegistry.AgentPermission> = emptySet(),
        block:               suspend (SandboxContext) -> T
    ): T {
        LoggingService.info(TAG, "AIRI SANDBOX_ENTER agent=$agentId goal=$goalId required=$requiredPermissions")

        requiredPermissions.forEach { perm ->
            if (!registry.check(agentId, perm)) {
                val msg = "Sandbox denied: agent=$agentId missing permission=$perm"
                LoggingService.warn(TAG, "AIRI SANDBOX_DENIED agent=$agentId perm=$perm")
                telemetry.report(AgentTelemetryEvent.AgentFailed(agentId, "PERMISSION_DENIED_$perm"))
                throw SandboxViolationException(msg)
            }
        }

        val workspace = WorkspaceRegistry.get(goalId)

        // Snapshot before executing so we can roll back on violation
        val snap = workspace.snapshot()
        workspace.log(ActionCategory.DECISION, agentId, "sandbox_enter",
            mapOf("goalId" to goalId, "snapId" to snap.id))

        val ctx = SandboxContext(agentId, registry.grantsFor(agentId), firewall, workspace)

        return try {
            val result = block(ctx)
            workspace.log(ActionCategory.DECISION, agentId, "sandbox_exit_ok")
            LoggingService.info(TAG, "AIRI SANDBOX_EXIT_OK agent=$agentId goal=$goalId")
            result
        } catch (e: ScopedPermissionRegistry.PermissionDeniedException) {
            LoggingService.warn(TAG, "AIRI SANDBOX_RUNTIME_VIOLATION agent=$agentId msg=${e.message}")
            telemetry.report(AgentTelemetryEvent.AgentFailed(agentId, "RUNTIME_PERMISSION_DENIED"))
            workspace.logError(agentId, "RUNTIME_PERMISSION_DENIED: ${e.message}")
            workspace.rollback(snap.id)
            throw SandboxViolationException("Runtime permission violation in agent=$agentId", e)
        } catch (e: ExecutionFirewall.UnknownToolException) {
            LoggingService.warn(TAG, "AIRI SANDBOX_UNKNOWN_TOOL agent=$agentId msg=${e.message}")
            telemetry.report(AgentTelemetryEvent.AgentFailed(agentId, "UNKNOWN_TOOL"))
            workspace.logError(agentId, "UNKNOWN_TOOL: ${e.message}")
            workspace.rollback(snap.id)
            throw SandboxViolationException("Unknown tool called by agent=$agentId", e)
        }
    }

    // ── Legacy overload (no goalId, no workspace) ─────────────────────────────

    suspend fun <T> execute(
        agentId:             String,
        requiredPermissions: Set<ScopedPermissionRegistry.AgentPermission> = emptySet(),
        block:               suspend (SandboxContext) -> T
    ): T = execute(agentId, agentId, requiredPermissions, block)

    // ── SandboxContext ────────────────────────────────────────────────────────

    /**
     * Injected into each sandboxed execution.
     *
     * @param workspace  Per-goal virtual file system + action log.
     */
    class SandboxContext(
        val agentId:   String,
        val grants:    Set<ScopedPermissionRegistry.AgentPermission>,
        private val firewall: ExecutionFirewall,
        val workspace: SandboxWorkspace
    ) {
        fun has(perm: ScopedPermissionRegistry.AgentPermission): Boolean = perm in grants
        fun guardTool(toolName: String)            = firewall.guard(agentId, toolName)
        fun allowsTool(toolName: String): Boolean  = firewall.allows(agentId, toolName)

        /** Convenience: write a result file. */
        fun writeResult(path: String, content: String) =
            workspace.writeFile(path, content, agentId)

        /** Convenience: read a file produced by a previous step. */
        fun readResult(path: String): String? =
            (workspace.readFile(path) as? com.airi.assistant.agent.workspace.WorkspaceResult.Ok)?.content
    }

    class SandboxViolationException(message: String, cause: Throwable? = null) :
        SecurityException(message, cause)
}
