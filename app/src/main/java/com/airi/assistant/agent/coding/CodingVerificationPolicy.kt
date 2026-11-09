package com.airi.assistant.agent.coding

/** Typed outcome recorded by a verification handler after the process has ended. */
data class CodingVerificationResult(
    val toolId: String,
    val exitCode: Int,
    val durationMs: Long,
    val outputSummary: String,
    val artifactPath: String? = null,
) {
    init {
        require(toolId == "run_tests" || toolId == "build")
        require(durationMs >= 0L)
    }

    val passed: Boolean get() = exitCode == 0
}

class CodingVerificationPolicy(
    private val workspacePolicy: CodingToolBoundaryPolicy = CodingToolBoundaryPolicy(),
    private val bounds: Bounds = Bounds(),
) {
    data class Bounds(
        val maxCommandChars: Int = 512,
        val maxTimeoutMs: Long = 300_000L,
        val maxOutputChars: Int = 64_000,
        val maxArtifactPathChars: Int = 512,
    ) {
        init {
            require(maxCommandChars > 0)
            require(maxTimeoutMs > 0L)
            require(maxOutputChars > 0)
            require(maxArtifactPathChars > 0)
        }
    }

    sealed interface Decision<out T> {
        data class Allowed<T>(val value: T) : Decision<T>
        data class Denied(val reason: String) : Decision<Nothing>
    }

    data class VerificationRequest(
        val toolId: String,
        val command: String,
        val workingDirectory: String,
        val timeoutMs: Long,
        val outputChars: Int,
        val artifactPath: String?,
    )

    fun evaluate(
        toolId: String,
        command: String,
        workingDirectory: String = "/",
        timeoutMs: Long = 120_000L,
        outputChars: Int = 16_000,
        artifactPath: String? = null,
    ): Decision<VerificationRequest> {
        if (toolId != "run_tests" && toolId != "build") return Decision.Denied("verification_tool_not_supported")
        val normalized = command.trim()
        if (normalized.isBlank()) return Decision.Denied("verification_command_empty")
        if (normalized.length > bounds.maxCommandChars) return Decision.Denied("verification_command_limit_exceeded")
        if (normalized.any { it in SHELL_META }) return Decision.Denied("verification_shell_syntax_denied")
        if (!isGradleVerificationCommand(toolId, normalized)) {
            return Decision.Denied("verification_command_not_allowlisted")
        }
        if (timeoutMs !in 1..bounds.maxTimeoutMs) return Decision.Denied("verification_timeout_limit_exceeded")
        if (outputChars !in 1..bounds.maxOutputChars) return Decision.Denied("verification_output_limit_exceeded")

        val cwd = workspacePolicy.workspacePath(workingDirectory)
        if (cwd is CodingToolBoundaryPolicy.Decision.Denied) return Decision.Denied(cwd.reason)
        if (artifactPath != null) {
            if (artifactPath.length > bounds.maxArtifactPathChars) return Decision.Denied("artifact_path_limit_exceeded")
            val artifact = workspacePolicy.workspacePath(artifactPath)
            if (artifact is CodingToolBoundaryPolicy.Decision.Denied) return Decision.Denied("artifact_${artifact.reason}")
        }
        return Decision.Allowed(
            VerificationRequest(
                toolId = toolId,
                command = normalized,
                workingDirectory = (cwd as CodingToolBoundaryPolicy.Decision.Allowed).value,
                timeoutMs = timeoutMs,
                outputChars = outputChars,
                artifactPath = artifactPath?.let {
                    (workspacePolicy.workspacePath(it) as CodingToolBoundaryPolicy.Decision.Allowed).value
                },
            ),
        )
    }

    fun validateResult(result: CodingVerificationResult): Decision<CodingVerificationResult> {
        if (result.durationMs > bounds.maxTimeoutMs) return Decision.Denied("verification_duration_exceeded")
        if (result.outputSummary.length > bounds.maxOutputChars) return Decision.Denied("verification_output_exceeded")
        result.artifactPath?.let { path ->
            val checked = workspacePolicy.workspacePath(path)
            if (checked is CodingToolBoundaryPolicy.Decision.Denied) return Decision.Denied("artifact_${checked.reason}")
        }
        return Decision.Allowed(result.copy(outputSummary = result.outputSummary.take(bounds.maxOutputChars)))
    }

    private fun isGradleVerificationCommand(toolId: String, command: String): Boolean {
        val tokens = command.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.firstOrNull() !in setOf("./gradlew", "gradlew")) return false
        val tasks = tokens.drop(1).filterNot { it.startsWith("-") }
        if (tasks.isEmpty()) return false
        if (tasks.any { it == "clean" || it.contains("publish", ignoreCase = true) || it.contains("sign", ignoreCase = true) }) return false
        return when (toolId) {
            "run_tests" -> tasks.all { it.contains("test", ignoreCase = true) || it.contains("check", ignoreCase = true) }
            "build" -> tasks.all {
                it.contains("assemble", ignoreCase = true) ||
                    it.contains("build", ignoreCase = true) ||
                    it.contains("compile", ignoreCase = true) ||
                    it.contains("lint", ignoreCase = true)
            }
            else -> false
        }
    }

    private companion object {
        val SHELL_META = setOf(';', '&', '|', '`', '$', '>', '<', '\n', '\r', '\u0000', '*', '?', '[', ']', '(', ')', '\\', '"', '\'')
    }
}
