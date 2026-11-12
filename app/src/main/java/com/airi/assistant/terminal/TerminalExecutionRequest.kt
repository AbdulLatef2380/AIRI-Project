package com.airi.assistant.terminal

import com.airi.assistant.agent.loop.AgentLoopExecutionContext

/**
 * Immutable ownership coordinates for one agent terminal request.
 *
 * The command is intentionally kept separate from the identity fields. The
 * gateway validates the identity before allocating or executing a process.
 */
data class TerminalExecutionRequest(
    val command: String,
    val context: AgentLoopExecutionContext,
    val executionId: String,
    val actionId: String,
    val traceId: String,
    val messageId: String,
) {
    fun isStructurallyValid(): Boolean =
        command.isNotBlank() &&
            context.isStructurallyValid() &&
            executionId.isSafeIdentifier() &&
            actionId.isSafeIdentifier() &&
            traceId.isSafeIdentifier() &&
            messageId.isSafeIdentifier()

    fun correlationLabel(): String =
        "task=${context.taskId} run=${context.runId} step=${context.stepId} " +
            "execution=$executionId action=$actionId trace=$traceId " +
            "session=${context.sourceSessionId} project=${context.projectId ?: "none"}"

    private fun String.isSafeIdentifier(): Boolean =
        matches(SAFE_IDENTIFIER)

    private companion object {
        val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._:-]{1,160}$")
    }
}
