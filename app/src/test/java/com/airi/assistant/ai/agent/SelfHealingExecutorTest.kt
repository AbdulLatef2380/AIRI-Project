package com.airi.assistant.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import com.airi.assistant.agent.loop.tool.ToolSchema

class SelfHealingExecutorTest {

    @Test
    fun generatesCorrectiveInstructionOnToolError() {
        val result = SelfHealingExecutor.recoverFromToolError(
            failedToolName = "WebSearch",
            errorMessage = "Rate limit exceeded",
            originalInput = "Search AI trends"
        )
        assertTrue(result.success)
        assertTrue(result.correctedPromptOrInput.contains("WebSearch"))
        assertEquals("Auto-corrected tool parameters for WebSearch", result.reason)
    }

    @Test
    fun preservesOriginalInputAndRedactsMultilineError() {
        val result = SelfHealingExecutor.recoverFromToolError(
            failedToolName = "fetch_url",
            errorMessage = "bad input\nsecret detail",
            originalInput = "اقرأ الصفحة ثم لخصها",
        )

        assertTrue(result.correctedPromptOrInput.startsWith("اقرأ الصفحة ثم لخصها"))
        assertTrue(result.correctedPromptOrInput.contains("bad input secret detail"))
    }

    @Test
    fun doesNotRetryUnknownWriteOutcomeWithoutIdempotencyKey() {
        assertEquals(
            SelfHealingExecutor.Decision.STOP_AND_EXPLAIN,
            SelfHealingExecutor.decision(
                ToolErrorCodes.EXECUTION_FAILED,
                ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION,
            ),
        )
    }

    @Test
    fun retriesInvalidReadArguments() {
        assertEquals(
            SelfHealingExecutor.Decision.RETRY_PARAMETER_REPAIR,
            SelfHealingExecutor.decision(
                ToolErrorCodes.INVALID_ARGUMENT,
                ToolSchema.SideEffectPolicy.READ_ONLY,
            ),
        )
    }
}
