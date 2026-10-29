package com.airi.assistant.ai.agent

import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Handles self-healing recovery when tool execution fails or returns malformed structured output.
 */
object SelfHealingExecutor {
    enum class Decision { RETRY_PARAMETER_REPAIR, STOP_AND_EXPLAIN }
    data class HealingResult(
        val success: Boolean,
        val correctedPromptOrInput: String,
        val reason: String
    )

    fun decision(
        errorCode: String,
        sideEffectPolicy: ToolSchema.SideEffectPolicy,
        hasIdempotencyKey: Boolean = false,
    ): Decision = when (errorCode) {
        ToolErrorCodes.INVALID_ARGUMENT -> Decision.RETRY_PARAMETER_REPAIR
        ToolErrorCodes.NETWORK_UNAVAILABLE,
        ToolErrorCodes.TIMEOUT -> if (
            sideEffectPolicy == ToolSchema.SideEffectPolicy.READ_ONLY
        ) Decision.RETRY_PARAMETER_REPAIR else Decision.STOP_AND_EXPLAIN
        ToolErrorCodes.AUTH_REQUIRED,
        ToolErrorCodes.PERMISSION_DENIED,
        ToolErrorCodes.NOT_CONNECTED,
        ToolErrorCodes.DUPLICATE_CALL -> Decision.STOP_AND_EXPLAIN
        ToolErrorCodes.EXECUTION_FAILED,
        ToolErrorCodes.CONNECTOR_FAILED -> if (hasIdempotencyKey &&
            sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY
        ) Decision.RETRY_PARAMETER_REPAIR else Decision.STOP_AND_EXPLAIN
        else -> Decision.STOP_AND_EXPLAIN
    }

    fun recoverFromToolError(failedToolName: String, errorMessage: String, originalInput: String): HealingResult {
        val safeError = errorMessage.replace(Regex("[\\r\\n]+"), " ").take(300)
        val repairInstruction = "The previous execution of tool '$failedToolName' failed with error: '$safeError'. " +
                "Re-evaluate the original request, correct only the invalid arguments, and retry only if the operation is safe and idempotent. " +
                "Never repeat a side effect without explicit confirmation."
        val preservedInput = originalInput.trim().ifBlank { "[original request unavailable]" }
        return HealingResult(
            success = true,
            correctedPromptOrInput = "$preservedInput\n\n[System Recovery Note: $repairInstruction]",
            reason = "Auto-corrected tool parameters for $failedToolName",
        )
    }
}
