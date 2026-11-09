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

    fun recoverFromToolError(
        failedToolName: String,
        errorMessage: String,
        originalInput: String,
        errorCode: String = ToolErrorCodes.INVALID_ARGUMENT,
        sideEffectPolicy: ToolSchema.SideEffectPolicy = ToolSchema.SideEffectPolicy.READ_ONLY,
        attempt: Int = 0,
        maxAttempts: Int = 2,
        hasIdempotencyKey: Boolean = false,
        schemaHint: String = "",
    ): HealingResult {
        val repair = ModelFacingRepairContract.buildPrompt(
            ModelFacingRepairContract.Request(
                toolName = failedToolName,
                errorCode = errorCode,
                sideEffectPolicy = sideEffectPolicy,
                originalInput = originalInput,
                attempt = attempt,
                maxAttempts = maxAttempts,
                hasIdempotencyKey = hasIdempotencyKey,
                schemaHint = schemaHint,
            )
        )
        val repairInstruction = repair.prompt ?: "Do not retry this tool failure. Explain the failure and the required next action."
        val preservedInput = originalInput.trim().ifBlank { "[original request unavailable]" }
        return HealingResult(
            success = repair.decision == ModelFacingRepairContract.Decision.REPAIR_ARGUMENTS ||
                repair.decision == ModelFacingRepairContract.Decision.RETRY_READ_TRANSIENT,
            correctedPromptOrInput = "$preservedInput\n\n[System Recovery Note: $repairInstruction]",
            reason = if (repair.decision == ModelFacingRepairContract.Decision.REPAIR_ARGUMENTS)
                "Auto-corrected tool parameters for $failedToolName"
            else repair.decision.name,
        )
    }
}
