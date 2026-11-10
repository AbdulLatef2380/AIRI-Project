package com.airi.assistant.ai.agent

import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import com.airi.assistant.agent.loop.tool.ToolSchema

/** Safe, bounded decision surface between tool failures and a model-facing repair prompt. */
object ModelFacingRepairContract {
    enum class Decision {
        REPAIR_ARGUMENTS,
        REPAIR_TOOL_CALL_FORMAT,
        RETRY_READ_TRANSIENT,
        EXPLAIN_AUTH,
        EXPLAIN_PERMISSION,
        EXPLAIN_APPROVAL,
        EXPLAIN_NOT_CONNECTED,
        STOP_UNCERTAIN_WRITE,
        STOP_FINAL,
    }

    data class Request(
        val toolName: String,
        val errorCode: String,
        val sideEffectPolicy: ToolSchema.SideEffectPolicy,
        val originalInput: String,
        val attempt: Int,
        val maxAttempts: Int = 2,
        val hasIdempotencyKey: Boolean = false,
        val schemaHint: String = "",
    )

    data class Result(val decision: Decision, val prompt: String? = null)

    fun decide(request: Request): Decision {
        if (request.attempt >= request.maxAttempts) return Decision.STOP_FINAL
        return when (request.errorCode) {
            ToolErrorCodes.INVALID_ARGUMENT -> Decision.REPAIR_ARGUMENTS
            ToolErrorCodes.NETWORK_UNAVAILABLE, ToolErrorCodes.TIMEOUT ->
                if (request.sideEffectPolicy == ToolSchema.SideEffectPolicy.READ_ONLY) Decision.RETRY_READ_TRANSIENT
                else Decision.STOP_UNCERTAIN_WRITE
            ToolErrorCodes.AUTH_REQUIRED -> Decision.EXPLAIN_AUTH
            ToolErrorCodes.PERMISSION_DENIED -> Decision.EXPLAIN_PERMISSION
            ToolErrorCodes.APPROVAL_REQUIRED -> Decision.EXPLAIN_APPROVAL
            ToolErrorCodes.NOT_CONNECTED -> Decision.EXPLAIN_NOT_CONNECTED
            ToolErrorCodes.EXECUTION_FAILED, ToolErrorCodes.CONNECTOR_FAILED ->
                if (request.hasIdempotencyKey && request.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY) {
                    Decision.REPAIR_ARGUMENTS
                } else Decision.STOP_UNCERTAIN_WRITE
            else -> Decision.STOP_FINAL
        }
    }

    fun buildPrompt(request: Request): Result {
        val decision = decide(request)
        val safeInput = request.originalInput.trim().take(2_000).ifBlank { "[original request unavailable]" }
        val hint = request.schemaHint.trim().take(1_000)
        val prompt = when (decision) {
            Decision.REPAIR_ARGUMENTS -> """
                Original request: $safeInput
                The tool '${request.toolName}' returned a typed argument error. Repair only the arguments required by its schema and call the same tool once. Do not invent permissions or change the user's goal. Attempt ${request.attempt + 1} of ${request.maxAttempts}.
                ${if (hint.isNotBlank()) "Schema hint: $hint" else "Use only the arguments already declared for this tool."}
            """.trimIndent()
            Decision.REPAIR_TOOL_CALL_FORMAT -> "Reply with one valid tool call using only the exposed schema. Do not add prose or undeclared arguments."
            Decision.RETRY_READ_TRANSIENT -> "The read-only tool failed transiently. Retry it once with the same validated arguments; do not perform a write."
            Decision.EXPLAIN_AUTH -> "The tool requires authorization. Stop tool execution and explain that the user must connect the service. Do not retry."
            Decision.EXPLAIN_PERMISSION -> "The tool is blocked by permission or approval policy. Stop and explain the required user action. Do not retry."
            Decision.EXPLAIN_APPROVAL -> "The tool requires explicit user approval for this action. Stop and ask for approval before retrying."
            Decision.EXPLAIN_NOT_CONNECTED -> "The service is not connected. Stop and ask the user to connect it; do not claim the data is absent."
            Decision.STOP_UNCERTAIN_WRITE -> "A write may have reached the external service with an uncertain outcome. Do not retry. Explain the uncertainty and ask the user to verify."
            Decision.STOP_FINAL -> null
        }
        return Result(decision, prompt)
    }
}
