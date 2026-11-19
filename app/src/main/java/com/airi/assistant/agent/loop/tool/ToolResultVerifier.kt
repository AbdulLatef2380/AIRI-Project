package com.airi.assistant.agent.loop.tool

/**
 * Final, side-effect-free verification rule for tool results.
 *
 * The dispatcher performs the expensive filesystem/hash checks. This boundary
 * refuses to publish a successful FILE_DIFF result unless the dispatcher also
 * returned a concrete changed-file or artifact identity.
 */
object ToolResultVerifier {
    data class Decision(val passed: Boolean, val code: String? = null, val reason: String? = null)

    fun verify(toolName: String, schema: ToolSchema?, result: ToolDispatcher.ToolResult): Decision {
        val success = result as? ToolDispatcher.ToolResult.Success
            ?: return Decision(false, ToolErrorCodes.EXECUTION_FAILED, "handler returned failure")
        if (success.output.isBlank()) return Decision(false, "verification_failed", "handler returned empty output")
        val sideEffect = schema?.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY
        if (sideEffect) {
            val evidence = success.evidence
            if (!evidence.semanticVerified || evidence.semanticReference.isNullOrBlank()) {
                return Decision(false, "evidence_required", "side-effect success has no semantic evidence")
            }
        }
        if (success.evidence.kind == ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF &&
            success.evidence.artifactId.isNullOrBlank() && success.evidence.changedFiles.isEmpty()
        ) {
            return Decision(false, "evidence_required", "file change has no artifact or changed-file identity")
        }
        return Decision(true)
    }

    fun isVerified(result: ToolDispatcher.ToolResult): Boolean {
        return verify("", ToolSchema("", ""), result).passed
    }
}
