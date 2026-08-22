package com.airi.assistant.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
