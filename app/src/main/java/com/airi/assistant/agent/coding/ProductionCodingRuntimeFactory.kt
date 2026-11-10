package com.airi.assistant.agent.coding

import android.content.Context
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.domain.execution.ExecutionOutcome
import com.airi.assistant.domain.tool.ArgumentSpec
import com.airi.assistant.domain.tool.ApprovalRequest
import com.airi.assistant.domain.tool.AuthorizationDecision
import com.airi.assistant.domain.tool.FixedWindowToolRateLimiter
import com.airi.assistant.domain.tool.InMemoryApprovalTokenStore
import com.airi.assistant.domain.tool.ToolAuthorizer
import com.airi.assistant.domain.tool.ToolExecutionPipeline
import com.airi.assistant.domain.tool.ToolHandler
import com.airi.assistant.domain.tool.ToolHandlerResult
import com.airi.assistant.domain.tool.ToolSandbox
import com.airi.assistant.domain.tool.ToolSpec
import com.airi.assistant.domain.tool.ToolSpecRegistry
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ScopedPermissionRegistry
import java.io.File
import java.util.UUID

/**
 * The application-owned construction root for the bounded coding runtime.
 *
 * Keeping this wiring in one place prevents CodingAgent from silently falling
 * back to an unbounded text delegation when the app has a usable sandbox.
 */
object ProductionCodingRuntimeFactory {
    fun create(
        context: Context,
        agentSandbox: AgentSandbox,
    ): CodingAgentRuntime {
        val appContext = context.applicationContext
        val approvalTokens = InMemoryApprovalTokenStore()
        val specs = CodingToolCatalog
        val sandbox = AgentSandboxToolSandbox(agentSandbox) { spec ->
            when (spec.id) {
                "read_file", "list_files", "search_files" ->
                    setOf(ScopedPermissionRegistry.AgentPermission.READ_FILES)
                "write_file", "apply_patch" ->
                    setOf(ScopedPermissionRegistry.AgentPermission.WRITE_FILES)
                else ->
                    setOf(ScopedPermissionRegistry.AgentPermission.EXECUTE_TERMINAL)
            }
        }
        val pipeline = ToolExecutionPipeline(
            specs = specs,
            authorizer = ToolAuthorizer { invocation ->
                if (invocation.spec.requiresConfirmation) {
                    AuthorizationDecision.NeedsApproval(
                        ApprovalRequest(
                            token = invocation.request.approvalToken.orEmpty(),
                            principal = invocation.request.principal,
                            toolId = invocation.request.toolId,
                            canonicalArguments = invocation.canonicalArguments,
                            sessionId = invocation.request.sessionId,
                            expiresAtEpochMs = System.currentTimeMillis() + 120_000L,
                        ),
                    )
                } else {
                    AuthorizationDecision.Allow
                }
            },
            approvalTokens = approvalTokens,
            rateLimiter = FixedWindowToolRateLimiter(maxCalls = 32, windowMs = 60_000L),
            sandbox = sandbox,
            handler = ProductionCodingToolHandler(appContext, SandboxManager(appContext)),
        )
        return CodingAgentRuntime(
            planner = ProductionCodingTaskPlanner,
            pipeline = pipeline,
        )
    }
}

/** Stable, bounded catalog for the coding vertical slice. */
private object CodingToolCatalog : ToolSpecRegistry {
    private val specs = listOf(
        spec("read_file", ToolSpec.Risk.READ, false, arg("path", true), arg("max_bytes", false, ArgumentSpec.Type.INTEGER)),
        spec("list_files", ToolSpec.Risk.READ, false, arg("path", false), arg("limit", false, ArgumentSpec.Type.INTEGER)),
        spec("search_files", ToolSpec.Risk.READ, false, arg("query", true), arg("path", false), arg("limit", false, ArgumentSpec.Type.INTEGER)),
        spec("write_file", ToolSpec.Risk.SIDE_EFFECT, true, arg("path", true), arg("content", true)),
        spec("apply_patch", ToolSpec.Risk.SIDE_EFFECT, true, arg("path", true), arg("patch", true)),
        spec("run_tests", ToolSpec.Risk.SIDE_EFFECT, true, *verificationArgs()),
        spec("build", ToolSpec.Risk.SIDE_EFFECT, true, *verificationArgs()),
    ).associateBy { it.id }

    override fun find(toolId: String): ToolSpec? = specs[toolId]

    private fun spec(id: String, risk: ToolSpec.Risk, confirmation: Boolean, vararg arguments: ArgumentSpec) =
        ToolSpec(
            id = id,
            arguments = arguments.toList(),
            capability = "coding",
            risk = risk,
            privacy = ToolSpec.PrivacyBoundary.LOCAL_ONLY,
            requiresConfirmation = confirmation,
            idempotent = !confirmation,
            handlerKey = id,
        )

    private fun verificationArgs() = arrayOf(
        arg("command", true),
        arg("working_directory", false),
        arg("timeout_ms", false, ArgumentSpec.Type.INTEGER),
        arg("output_chars", false, ArgumentSpec.Type.INTEGER),
        arg("artifact_path", false),
    )

    private fun arg(name: String, required: Boolean, type: ArgumentSpec.Type = ArgumentSpec.Type.STRING) =
        ArgumentSpec(name, type, required)
}

/**
 * Deterministic planner boundary. LLM-produced plans can be supplied through
 * dependencyResults["coding_plan"]. Simple verification requests are planned
 * locally; ambiguous generation requests fail closed instead of inventing writes.
 */
private object ProductionCodingTaskPlanner : CodingTaskPlanner {
    override suspend fun plan(input: String, context: SubAgentContext): CodingTask? {
        val encoded = context.dependencyResults["coding_plan"]
        if (!encoded.isNullOrBlank()) return parsePlan(encoded, context)
        val lower = input.lowercase()
        val verificationTool = when {
            "test" in lower || "اختبار" in lower -> "run_tests"
            "build" in lower || "assemble" in lower || "بناء" in lower -> "build"
            else -> null
        } ?: return null
        val command = if (verificationTool == "build") "./gradlew assembleDebug" else "./gradlew testDebugUnitTest"
        return CodingTask(
            taskId = context.parentTaskId.ifBlank { "coding-${UUID.randomUUID()}" },
            principal = "coding-agent",
            sessionId = context.executionId.ifBlank { context.sessionId },
            generationId = System.currentTimeMillis(),
            actions = listOf(
                CodingAction(
                    stepId = "verify",
                    toolId = verificationTool,
                    arguments = mapOf("command" to command, "working_directory" to "/"),
                    approvalToken = context.dependencyResults["coding_approval_token"],
                ),
            ),
        )
    }

    private fun parsePlan(encoded: String, context: SubAgentContext): CodingTask? {
        val actions = encoded.lineSequence().mapIndexedNotNull { index, line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 2) return@mapIndexedNotNull null
            val tool = parts[0].trim()
            val args = parts[2].orEmpty().split(';').mapNotNull { pair ->
                val equals = pair.indexOf('=')
                if (equals <= 0) null else pair.substring(0, equals).trim() to pair.substring(equals + 1)
            }.toMap()
            CodingAction("step-${index + 1}", tool, args, context.dependencyResults["coding_approval_token"])
        }.toList()
        if (actions.isEmpty()) return null
        return CodingTask(
            taskId = context.parentTaskId.ifBlank { "coding-${UUID.randomUUID()}" },
            principal = "coding-agent",
            sessionId = context.executionId.ifBlank { context.sessionId },
            generationId = System.currentTimeMillis(),
            actions = actions,
        )
    }
}

private class ProductionCodingToolHandler(
    private val context: Context,
    private val sandboxManager: SandboxManager,
) : ToolHandler {
    private val root: File = File(context.filesDir, "coding-workspaces")

    override suspend fun execute(invocation: com.airi.assistant.domain.tool.ValidatedAuthorizedInvocation): ToolHandlerResult {
        val args = invocation.arguments.canonical
        val workspace = workspace(invocation.request.sessionId)
        return when (invocation.request.toolId) {
            "read_file" -> read(workspace, args["path"].orEmpty(), args["max_bytes"]?.toIntOrNull() ?: 64_000)
            "list_files" -> list(workspace, args["path"].orEmpty().ifBlank { "/" }, args["limit"]?.toIntOrNull() ?: 200)
            "search_files" -> search(workspace, args["path"].orEmpty().ifBlank { "/" }, args["query"].orEmpty(), args["limit"]?.toIntOrNull() ?: 100)
            "write_file" -> write(workspace, args["path"].orEmpty(), args["content"].orEmpty())
            "apply_patch" -> applyPatch(workspace, args["path"].orEmpty(), args["patch"].orEmpty())
            "run_tests", "build" -> verify(invocation.request.toolId, args)
            else -> ToolHandlerResult.Failure("unknown_coding_handler")
        }
    }

    private fun workspace(sessionId: String): File = File(root, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.mkdirs() }

    private fun safeFile(root: File, path: String): File? {
        val file = File(root, path.trimStart('/'))
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        return canonical.takeIf { it.path == canonicalRoot.path || it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun read(root: File, path: String, maxBytes: Int): ToolHandlerResult {
        val file = safeFile(root, path) ?: return ToolHandlerResult.Failure("path_traversal")
        if (!file.isFile) return ToolHandlerResult.Failure("file_not_found")
        if (file.length() > maxBytes) return ToolHandlerResult.Failure("file_size_limit_exceeded")
        return ToolHandlerResult.Success(file.readText().take(maxBytes))
    }

    private fun list(root: File, path: String, limit: Int): ToolHandlerResult {
        val directory = safeFile(root, path) ?: return ToolHandlerResult.Failure("path_traversal")
        if (!directory.isDirectory) return ToolHandlerResult.Failure("directory_not_found")
        return ToolHandlerResult.Success(directory.walkTopDown().filter { it != directory }.take(limit.coerceAtLeast(1)).joinToString("\n") { "/" + it.relativeTo(root).path.replace(File.separatorChar, '/') })
    }

    private fun search(root: File, path: String, query: String, limit: Int): ToolHandlerResult {
        if (query.isBlank()) return ToolHandlerResult.Failure("query_empty")
        val directory = safeFile(root, path) ?: return ToolHandlerResult.Failure("path_traversal")
        if (!directory.isDirectory) return ToolHandlerResult.Failure("directory_not_found")
        return ToolHandlerResult.Success(directory.walkTopDown().filter { it.isFile }.mapNotNull { file ->
            runCatching { if (file.readText().contains(query)) "/" + file.relativeTo(root).path.replace(File.separatorChar, '/') else null }.getOrNull()
        }.take(limit.coerceAtLeast(1)).joinToString("\n"))
    }

    private fun write(root: File, path: String, content: String): ToolHandlerResult {
        val file = safeFile(root, path) ?: return ToolHandlerResult.Failure("path_traversal")
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        temp.writeText(content)
        if (!temp.renameTo(file)) return ToolHandlerResult.Failure("atomic_write_failed")
        return ToolHandlerResult.Success("written:${file.name}:${content.length}")
    }

    private fun applyPatch(root: File, path: String, patch: String): ToolHandlerResult {
        val file = safeFile(root, path) ?: return ToolHandlerResult.Failure("path_traversal")
        if (!file.isFile) return ToolHandlerResult.Failure("file_not_found")
        // The bounded runtime records the patch as an auditable sidecar. A later
        // repair planner can consume it without allowing arbitrary shell patches.
        val sidecar = File(file.parentFile, ".${file.name}.airi.patch")
        sidecar.writeText(patch)
        return ToolHandlerResult.Success("patch_recorded:${file.name}")
    }

    private suspend fun verify(toolId: String, args: Map<String, String>): ToolHandlerResult {
        val session = sandboxManager.createSession("coding-$toolId")
            ?: return ToolHandlerResult.Failure("sandbox_session_unavailable")
        if (!session.beginUse()) {
            sandboxManager.closeSession(session.sessionId)
            return ToolHandlerResult.Failure("sandbox_session_unavailable")
        }
        return try {
            val command = args["command"].orEmpty()
            val result = SandboxExecutor(session).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.SHELL_COMMAND,
                    command = command,
                    timeoutMs = args["timeout_ms"]?.toLongOrNull() ?: 120_000L,
                ),
            )
            when (result) {
                is SandboxExecutor.ExecutionResult.Success -> ToolHandlerResult.Success("exit_code=${result.exitCode} ${result.output.take(16_000)}")
                is SandboxExecutor.ExecutionResult.Timeout -> ToolHandlerResult.Timeout
                is SandboxExecutor.ExecutionResult.Failure -> ToolHandlerResult.Failure("exit_code=${result.exitCode} ${result.error}")
                is SandboxExecutor.ExecutionResult.SecurityViolation -> ToolHandlerResult.Failure("verification_security:${result.reason}")
                SandboxExecutor.ExecutionResult.UnsupportedOnDevice -> ToolHandlerResult.Failure("verification_unsupported_on_device")
            }
        } finally {
            session.endUse()
            sandboxManager.closeSession(session.sessionId)
        }
    }
}
