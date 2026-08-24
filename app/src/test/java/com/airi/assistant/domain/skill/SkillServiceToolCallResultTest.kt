package com.airi.assistant.domain.skill

import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillServiceToolCallResultTest {

    @Test
    fun failedToolExecutionReturnsFailedResult() {
        val result = SkillService.resultForToolExecution(
            ToolCall("github_get_repos", emptyMap()),
            ToolResult(success = false, data = "", error = "Network unavailable")
        )

        assertTrue(result is SkillService.ToolCallResult.Failed)
        val failure = result as SkillService.ToolCallResult.Failed
        assertEquals("github_get_repos", failure.toolName)
        assertEquals("Network unavailable", failure.errorMessage)
    }

    @Test
    fun successfulToolExecutionRetainsExecutedResult() {
        val toolCall = ToolCall("github_get_repos", emptyMap())
        val toolResult = ToolResult(success = true, data = "[]")

        val result = SkillService.resultForToolExecution(toolCall, toolResult)

        assertTrue(result is SkillService.ToolCallResult.Executed)
        val executed = result as SkillService.ToolCallResult.Executed
        assertEquals(toolCall, executed.toolCall)
        assertEquals(toolResult, executed.result)
    }
}
