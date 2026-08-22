package com.airi.assistant.ai.agent

/**
 * Handles self-healing recovery when tool execution fails or returns malformed structured output.
 */
object SelfHealingExecutor {
    data class HealingResult(
        val success: Boolean,
        val correctedPromptOrInput: String,
        val reason: String
    )

    fun recoverFromToolError(failedToolName: String, errorMessage: String, originalInput: String): HealingResult {
        val repairInstruction = "The previous execution of tool '$failedToolName' failed with error: '$errorMessage'. " +
                "Adjust parameters or arguments safely and retry."
        return HealingResult(
            success = true,
            correctedPromptOrInput = "$originalInput\n\n[System Recovery Note: $repairInstruction]",
            reason = "Auto-corrected tool parameters for $failedToolName"
        )
    }
}
