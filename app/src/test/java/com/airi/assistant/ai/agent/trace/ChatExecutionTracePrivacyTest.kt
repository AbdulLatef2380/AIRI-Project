package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionTracePrivacyTest {

    @Test
    fun activeChatTracePathRedactsSensitiveInputEventsAndAnswerBeforeStorage() {
        val bearer = "secret-agent-token-1234567890"
        val apiKey = "fixture-google-key-value-1234567890"
        val cookie = "private-cookie-value"
        val privatePath = "/data/user/0/com.airi.assistant/private.txt"
        val executionId = "exec-privacy"
        val mapped = ChatExecutionTraceMapper.toAgentTrace(
            executionId = executionId,
            sessionId = "session-privacy",
            messageId = 91L,
            userInput = "Authorization: Bearer $bearer",
            events = listOf(
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 1,
                    timestampMs = 1_001L,
                    kind = ExecutionTraceKind.TOOL_FAILED,
                    summary = "password=unsafe-value",
                    detail = "api_key=$apiKey cookie=$cookie path=$privatePath",
                    actionId = "file_read",
                ),
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 2,
                    timestampMs = 1_002L,
                    kind = ExecutionTraceKind.RECOVERING,
                    summary = "Recovering step",
                    actionId = "file_read",
                ),
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 3,
                    timestampMs = 1_003L,
                    kind = ExecutionTraceKind.COMPLETED,
                    summary = "done",
                ),
            ),
            answerText = "Finished reading $privatePath",
            model = "gpt-4.1",
            provider = "openai",
        )

        val manager = AgentTraceManager()
        manager.recordChatExecution(requireNotNull(mapped))
        val trace = requireNotNull(manager.getTrace(executionId))
        val flattened = listOf(
            trace.originalInput,
            trace.steps.single().outputSummary,
            trace.steps.single().error.orEmpty(),
            trace.finalResult,
        ).joinToString("\n")

        assertTrue(flattened.contains("[SECRET_REDACTED]") || flattened.contains("[KEY_REDACTED]"))
        assertTrue(flattened.contains("[PATH_REDACTED]"))
        assertFalse(flattened.contains(bearer))
        assertFalse(flattened.contains(cookie))
        assertFalse(flattened.contains("unsafe-value"))
        assertFalse(flattened.contains(apiKey))
        assertFalse(flattened.contains(privatePath))
        assertTrue(trace.success)
        assertTrue(trace.steps.any { it.error != null })
        assertEquals("gpt-4.1", trace.model)
        assertEquals("openai", trace.provider)
    }

    @Test
    fun traceWithoutTerminalEvidenceIsNeverReportedAsSuccessful() {
        val executionId = "exec-incomplete"
        val mapped = ChatExecutionTraceMapper.toAgentTrace(
            executionId = executionId,
            sessionId = "session-incomplete",
            messageId = 92L,
            userInput = "run task",
            events = listOf(
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 1,
                    timestampMs = 2_001L,
                    kind = ExecutionTraceKind.TOOL_STARTED,
                    summary = "Running tool",
                    actionId = "tool-1",
                ),
            ),
            answerText = "partial",
        )

        assertFalse(requireNotNull(mapped).success)
    }

    @Test
    fun completedTerminalDoesNotMaskAnUnrecoveredFailure() {
        val executionId = "exec-unrecovered"
        val mapped = ChatExecutionTraceMapper.toAgentTrace(
            executionId = executionId,
            sessionId = "session-unrecovered",
            messageId = 93L,
            userInput = "run task",
            events = listOf(
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 1,
                    timestampMs = 3_001L,
                    kind = ExecutionTraceKind.TOOL_FAILED,
                    summary = "Tool failed",
                    actionId = "tool-1",
                ),
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 2,
                    timestampMs = 3_002L,
                    kind = ExecutionTraceKind.COMPLETED,
                    summary = "done",
                ),
            ),
            answerText = "done",
        )

        assertFalse(requireNotNull(mapped).success)
    }

    @Test
    fun terminalToolStepRetainsInputParamsAndParentId() {
        val executionId = "exec-structured-step"
        val mapped = ChatExecutionTraceMapper.toAgentTrace(
            executionId = executionId,
            sessionId = "session-structured-step",
            messageId = 94L,
            userInput = "read the file",
            events = listOf(
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 1,
                    timestampMs = 4_001L,
                    kind = ExecutionTraceKind.TOOL_STARTED,
                    summary = "Running tool",
                    actionId = "tool-1",
                    parentId = "step-1",
                    inputParams = mapOf("path" to "/safe/README.md"),
                ),
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 2,
                    timestampMs = 4_002L,
                    kind = ExecutionTraceKind.TOOL_COMPLETED,
                    summary = "Tool completed",
                    actionId = "tool-1",
                    parentId = "step-1",
                    inputParams = mapOf("path" to "/safe/README.md"),
                ),
                ExecutionTraceEvent(
                    executionId = executionId,
                    sequence = 3,
                    timestampMs = 4_003L,
                    kind = ExecutionTraceKind.COMPLETED,
                    summary = "done",
                ),
            ),
            answerText = "done",
        )

        val step = requireNotNull(mapped).steps.single()
        assertEquals(mapOf("path" to "/safe/README.md"), step.inputParams)
        assertEquals("step-1", step.parentId)
    }
}
