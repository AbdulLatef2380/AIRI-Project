package com.airi.assistant.agent.coding

import com.airi.assistant.domain.execution.ExecutionOutcome
import com.airi.assistant.domain.tool.ToolRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingAgentOrchestratorTest {
    private fun task(vararg actions: CodingAction) = CodingTask(
        taskId = "task-1",
        principal = "coding-agent",
        sessionId = "workspace-1",
        generationId = 7L,
        actions = actions.toList(),
    )

    @Test
    fun executesOrderedActionsAndReturnsBoundedEvidence() = runBlocking {
        val seen = mutableListOf<String>()
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            seen += request.toolId
            ExecutionOutcome.Success(request.requestId, request.generationId, "created")
        })

        val report = agent.execute(
            task(
                CodingAction("step-1", "write_file", mapOf("path" to "/main.py", "content" to "print(1)")),
                CodingAction("step-2", "check_syntax"),
            ),
        )

        assertEquals(CodingAgentStatus.SUCCEEDED, report.status)
        assertEquals(listOf("write_file", "check_syntax"), seen)
        assertTrue(report.modelContext().contains("status=SUCCEEDED"))
        assertTrue(report.remainingStepIds.isEmpty())
    }

    @Test
    fun failureStopsLaterActionsAndReportsRemainingSteps() = runBlocking {
        val seen = mutableListOf<String>()
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            seen += request.toolId
            ExecutionOutcome.Failure(request.requestId, request.generationId, "denied:policy")
        })

        val report = agent.execute(
            task(
                CodingAction("step-1", "write_file", mapOf("path" to "/main.py", "content" to "print(1)")),
                CodingAction("step-2", "terminal_execute"),
            ),
        )

        assertEquals(CodingAgentStatus.FAILED, report.status)
        assertEquals(listOf("write_file"), seen)
        assertEquals(listOf("step-2"), report.remainingStepIds)
        assertEquals("FAILED", report.steps.single().status)
    }

    @Test
    fun emptyPlanIsBlockedWithoutCallingExecutor() = runBlocking {
        var calls = 0
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            calls++
            ExecutionOutcome.Empty(request.requestId, request.generationId)
        })

        val report = agent.execute(task())

        assertEquals(CodingAgentStatus.BLOCKED, report.status)
        assertEquals(0, calls)
    }

    @Test
    fun modelContextRedactsSecretsAndIsBounded() = runBlocking {
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            ExecutionOutcome.Success(
                request.requestId,
                request.generationId,
                "api_key=super-secret-value " + "x".repeat(500),
            )
        })

        val report = agent.execute(
            task(CodingAction("step-1", "write_file", mapOf("path" to "/main.py", "content" to "print(1)"))),
        )
        val context = report.modelContext(maxChars = 120)

        assertTrue(context.length <= 120)
        assertFalse(context.contains("super-secret-value"))
    }

    @Test
    fun cancellationIsPreservedAndLaterActionsRemainUnexecuted() = runBlocking {
        var calls = 0
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            calls++
            throw CancellationException("cancelled")
        })

        val report = agent.execute(
            task(
                CodingAction("step-1", "terminal_execute", mapOf("command" to "ls")),
                CodingAction("step-2", "build"),
            ),
        )

        assertEquals(CodingAgentStatus.CANCELLED, report.status)
        assertEquals(1, calls)
        assertEquals(listOf("step-2"), report.remainingStepIds)
    }

    @Test
    fun genericVerificationCommandIsBlockedBeforeExecutor() = runBlocking {
        var calls = 0
        val agent = CodingAgentOrchestrator(CodingToolExecutor { request ->
            calls++
            ExecutionOutcome.Success(request.requestId, request.generationId, "unexpected")
        })

        val report = agent.execute(
            task(
                CodingAction(
                    "step-1",
                    "run_tests",
                    mapOf("command" to "python -m pytest"),
                ),
            ),
        )

        assertEquals(CodingAgentStatus.FAILED, report.status)
        assertEquals("verification_command_not_allowlisted", report.steps.single().evidence)
        assertEquals(0, calls)
    }
}
