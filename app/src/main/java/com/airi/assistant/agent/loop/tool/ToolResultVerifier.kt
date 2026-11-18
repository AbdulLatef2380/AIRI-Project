package com.airi.assistant.agent.loop.tool

/**
 * Final, side-effect-free verification rule for tool results.
 *
 * The dispatcher performs the expensive filesystem/hash checks. This boundary
 * refuses to publish a successful FILE_DIFF result unless the dispatcher also
 * returned a concrete changed-file or artifact identity.
 */
object ToolResultVerifier {
    fun isVerified(result: ToolDispatcher.ToolResult): Boolean {
        val success = result as? ToolDispatcher.ToolResult.Success ?: return false
        if (success.output.isBlank()) return false
        return when (success.evidence.kind) {
            ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF ->
                success.evidence.artifactId?.isNotBlank() == true || success.evidence.changedFiles.isNotEmpty()
            else -> true
        }
    }
}
