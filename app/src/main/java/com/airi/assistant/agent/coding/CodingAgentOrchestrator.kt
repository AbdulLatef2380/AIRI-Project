package com.airi.assistant.agent.coding

import com.airi.assistant.domain.execution.ExecutionOutcome
import com.airi.assistant.domain.execution.RequestId
import com.airi.assistant.domain.tool.ToolSandbox
import com.airi.assistant.domain.tool.ToolSpec
import com.airi.assistant.domain.tool.ToolExecutionPipeline
import com.airi.assistant.domain.tool.ToolRequest
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ScopedPermissionRegistry
import kotlinx.coroutines.CancellationException

/** A bounded, typed action selected by the coding request planner. */
data class CodingAction(
    val stepId: String,
    val toolId: String,
    val arguments: Map<String, String> = emptyMap(),
    val approvalToken: String? = null,
) {
    init {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(toolId.isNotBlank()) { "toolId must not be blank" }
    }
}

data class CodingTask(
    val taskId: String,
    val principal: String,
    val sessionId: String,
    val generationId: Long,
    val actions: List<CodingAction>,
) {
    init {
        require(taskId.isNotBlank())
        require(principal.isNotBlank())
        require(sessionId.isNotBlank())
        require(generationId >= 0L)
        require(actions.map { it.stepId }.distinct().size == actions.size) { "stepId must be unique" }
    }
}

fun interface CodingToolExecutor {
    suspend fun execute(request: ToolRequest): ExecutionOutcome
}

/** Production adapter: CodingAgent has no alternate execution path around the pipeline. */
class ToolPipelineCodingExecutor(
    private val pipeline: ToolExecutionPipeline,
) : CodingToolExecutor {
    override suspend fun execute(request: ToolRequest): ExecutionOutcome = pipeline.execute(request)
}

/**
 * Adapter used when constructing the pipeline for CodingAgent. It makes the
 * workspace-scoped AgentSandbox an explicit, mandatory boundary for handlers.
 */
class AgentSandboxToolSandbox(
    private val sandbox: AgentSandbox,
    private val requiredPermissions: (ToolSpec) -> Set<ScopedPermissionRegistry.AgentPermission> = { emptySet() },
) : ToolSandbox {
    override suspend fun <T> execute(
        invocation: com.airi.assistant.domain.tool.ValidatedAuthorizedInvocation,
        block: suspend () -> T,
    ): T = sandbox.execute(
        agentId = invocation.request.principal,
        goalId = invocation.request.sessionId,
        requiredPermissions = requiredPermissions(invocation.spec),
    ) {
        block()
    }
}

enum class CodingAgentStatus { SUCCEEDED, FAILED, BLOCKED, CANCELLED }

data class CodingStepEvidence(
    val stepId: String,
    val toolId: String,
    val status: String,
    val evidence: String = "",
)

data class CodingAgentExecutionReport(
    val taskId: String,
    val status: CodingAgentStatus,
    val steps: List<CodingStepEvidence>,
    val remainingStepIds: List<String>,
) {
    /** Safe, bounded representation intended for model context and trace summaries. */
    fun modelContext(maxChars: Int = DEFAULT_CONTEXT_CHARS): String {
        require(maxChars > 0)
        val body = buildString {
            append("coding_task=").append(taskId).append('\n')
            append("status=").append(status.name).append('\n')
            steps.forEach { step ->
                append("step=").append(step.stepId)
                    .append(" tool=").append(step.toolId)
                    .append(" status=").append(step.status)
                if (step.evidence.isNotBlank()) append(" evidence=").append(redact(step.evidence))
                append('\n')
            }
            if (remainingStepIds.isNotEmpty()) {
                append("remaining=").append(remainingStepIds.joinToString(",")).append('\n')
            }
        }
        return body.take(maxChars)
    }

    private companion object {
        const val DEFAULT_CONTEXT_CHARS = 4_000
        private val SECRET = Regex(
            "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+",
        )

        fun redact(value: String): String = SECRET.replace(value, "$1=[REDACTED]")
    }
}

/**
 * Executes a planned coding task serially. Authorization, approval, rate limiting,
 * sandboxing, argument validation, and handlers remain exclusively in the pipeline.
 */
class CodingAgentOrchestrator(
    private val executor: CodingToolExecutor,
    private val maxEvidenceChars: Int = 2_000,
    private val boundaryPolicy: CodingToolBoundaryPolicy = CodingToolBoundaryPolicy(),
    private val verificationPolicy: CodingVerificationPolicy = CodingVerificationPolicy(boundaryPolicy),
) : CodingTaskRunner {
    init {
        require(maxEvidenceChars > 0)
    }

    override suspend fun execute(task: CodingTask): CodingAgentExecutionReport {
        if (task.actions.isEmpty()) {
            return CodingAgentExecutionReport(
                taskId = task.taskId,
                status = CodingAgentStatus.BLOCKED,
                steps = emptyList(),
                remainingStepIds = emptyList(),
            )
        }

        val evidence = mutableListOf<CodingStepEvidence>()
        for ((index, action) in task.actions.withIndex()) {
            preflight(action)?.let { reason ->
                evidence += CodingStepEvidence(action.stepId, action.toolId, "FAILED", reason)
                return CodingAgentExecutionReport(
                    task.taskId,
                    CodingAgentStatus.FAILED,
                    evidence.toList(),
                    task.actions.drop(index + 1).map { it.stepId },
                )
            }
            val request = ToolRequest(
                requestId = RequestId.new(),
                generationId = task.generationId,
                principal = task.principal,
                sessionId = task.sessionId,
                toolId = action.toolId,
                rawArguments = canonicalArguments(action),
                approvalToken = action.approvalToken,
            )
            val outcome = try {
                executor.execute(request)
            } catch (cancelled: CancellationException) {
                evidence += CodingStepEvidence(action.stepId, action.toolId, "CANCELLED")
                return CodingAgentExecutionReport(
                    task.taskId,
                    CodingAgentStatus.CANCELLED,
                    evidence.toList(),
                    task.actions.drop(index + 1).map { it.stepId },
                )
            }

            val step = when (outcome) {
                is ExecutionOutcome.Success -> if (action.toolId == "run_tests" || action.toolId == "build") {
                    validateVerification(action, outcome.text)
                } else CodingStepEvidence(
                    action.stepId,
                    action.toolId,
                    "SUCCEEDED",
                    outcome.text.take(maxEvidenceChars),
                )
                is ExecutionOutcome.Empty -> CodingStepEvidence(action.stepId, action.toolId, "EMPTY")
                is ExecutionOutcome.Failure -> CodingStepEvidence(
                    action.stepId,
                    action.toolId,
                    "FAILED",
                    outcome.reason.take(maxEvidenceChars),
                )
                is ExecutionOutcome.Timeout -> CodingStepEvidence(action.stepId, action.toolId, "TIMEOUT")
                is ExecutionOutcome.Cancelled -> CodingStepEvidence(action.stepId, action.toolId, "CANCELLED")
                is ExecutionOutcome.Partial -> CodingStepEvidence(
                    action.stepId,
                    action.toolId,
                    "PARTIAL_${outcome.terminalCause.name}",
                    outcome.text.take(maxEvidenceChars),
                )
            }
            evidence += step
            if (step.status != "SUCCEEDED") {
                return CodingAgentExecutionReport(
                    task.taskId,
                    if (step.status == "CANCELLED") CodingAgentStatus.CANCELLED else CodingAgentStatus.FAILED,
                    evidence.toList(),
                    task.actions.drop(index + 1).map { it.stepId },
                )
            }
        }
        return CodingAgentExecutionReport(
            task.taskId,
            CodingAgentStatus.SUCCEEDED,
            evidence.toList(),
            emptyList(),
        )
    }

    private fun preflight(action: CodingAction): String? {
        val args = action.arguments
        fun decisionReason(decision: CodingToolBoundaryPolicy.Decision<*>): String? =
            (decision as? CodingToolBoundaryPolicy.Decision.Denied)?.reason
        fun optionalInt(name: String): Int? = args[name]?.toIntOrNull()

        return when (action.toolId) {
            "read_file" -> {
                val path = args["path"] ?: return "missing_path"
                if ("max_bytes" in args && optionalInt("max_bytes") == null) return "invalid_max_bytes"
                decisionReason(optionalInt("max_bytes")?.let { boundaryPolicy.readFile(path, it) }
                    ?: boundaryPolicy.readFile(path))
            }
            "list_files" -> {
                val path = args["path"] ?: "/"
                if ("limit" in args && optionalInt("limit") == null) return "invalid_limit"
                decisionReason(optionalInt("limit")?.let { boundaryPolicy.listFiles(path, it) }
                    ?: boundaryPolicy.listFiles(path))
            }
            "search_files" -> {
                val query = args["query"] ?: return "missing_query"
                val path = args["path"] ?: "/"
                if ("limit" in args && optionalInt("limit") == null) return "invalid_limit"
                decisionReason(optionalInt("limit")?.let { boundaryPolicy.searchFiles(query, path, it) }
                    ?: boundaryPolicy.searchFiles(query, path))
            }
            "write_file" -> {
                val path = args["path"] ?: return "missing_path"
                val content = args["content"] ?: return "missing_content"
                decisionReason(boundaryPolicy.writeFile(path, content.toByteArray(Charsets.UTF_8).size))
            }
            "apply_patch" -> {
                val path = args["path"] ?: return "missing_path"
                val patch = args["patch"] ?: return "missing_patch"
                decisionReason(boundaryPolicy.applyPatch(path, patch.toByteArray(Charsets.UTF_8).size))
            }
            "terminal_execute" -> {
                val command = args["command"] ?: return "missing_command"
                val cwd = args["working_directory"] ?: "/"
                val timeout = args["timeout_ms"]?.toLongOrNull() ?: if ("timeout_ms" in args) return "invalid_timeout_ms" else 30_000L
                val output = args["output_chars"]?.toIntOrNull() ?: if ("output_chars" in args) return "invalid_output_chars" else 16_000
                decisionReason(boundaryPolicy.terminalExecute(command, cwd, timeout, output))
            }
            "run_tests", "build" -> {
                val command = args["command"] ?: return "missing_command"
                val cwd = args["working_directory"] ?: "/"
                val timeout = args["timeout_ms"]?.toLongOrNull() ?: if ("timeout_ms" in args) return "invalid_timeout_ms" else 120_000L
                val output = args["output_chars"]?.toIntOrNull() ?: if ("output_chars" in args) return "invalid_output_chars" else 16_000
                when (val decision = verificationPolicy.evaluate(
                    toolId = action.toolId,
                    command = command,
                    workingDirectory = cwd,
                    timeoutMs = timeout,
                    outputChars = output,
                    artifactPath = args["artifact_path"],
                )) {
                    is CodingVerificationPolicy.Decision.Allowed -> null
                    is CodingVerificationPolicy.Decision.Denied -> decision.reason
                }
            }
            else -> null
        }
    }

    private fun validateVerification(action: CodingAction, output: String): CodingStepEvidence {
        val exitCode = Regex("(?:exit_code|exit)\\s*=\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (output.contains("passed", ignoreCase = true)) 0 else 1
        return when (val decision = verificationPolicy.validateResult(
            CodingVerificationResult(
                toolId = action.toolId,
                exitCode = exitCode,
                durationMs = 0L,
                outputSummary = output,
                artifactPath = action.arguments["artifact_path"],
            ),
        )) {
            is CodingVerificationPolicy.Decision.Allowed -> CodingStepEvidence(
                action.stepId,
                action.toolId,
                if (decision.value.passed) "SUCCEEDED" else "FAILED",
                decision.value.outputSummary.take(maxEvidenceChars),
            )
            is CodingVerificationPolicy.Decision.Denied -> CodingStepEvidence(
                action.stepId,
                action.toolId,
                "FAILED",
                decision.reason,
            )
        }
    }

    private fun canonicalArguments(action: CodingAction): Map<String, String> =
        action.arguments.toMutableMap().apply {
            listOf("path", "working_directory", "artifact_path").forEach { key ->
                this[key]?.let { value ->
                    (boundaryPolicy.workspacePath(value) as? CodingToolBoundaryPolicy.Decision.Allowed)
                        ?.value
                        ?.let { normalized -> this[key] = normalized }
                }
            }
            this["command"]?.let { this["command"] = it.trim() }
        }
}
