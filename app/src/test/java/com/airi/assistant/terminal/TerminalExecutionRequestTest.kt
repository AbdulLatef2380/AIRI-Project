package com.airi.assistant.terminal

import com.airi.assistant.agent.loop.AgentLoopExecutionContext
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TerminalExecutionRequestTest {
    private fun context() = AgentLoopExecutionContext(
        taskId = "task-1",
        missionId = "mission-1",
        projectId = "project-1",
        runId = "run-1",
        stepId = "step-1",
        agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
        sourceSessionId = "session-1",
    )

    @Test
    fun completeCorrelationIsAccepted() {
        val request = TerminalExecutionRequest(
            command = "git status",
            context = context(),
            executionId = "execution-1",
            actionId = "action-1",
            traceId = "trace-execution-1",
            messageId = "message-1",
        )
        assertTrue(request.isStructurallyValid())
        assertTrue(request.correlationLabel().contains("task=task-1"))
        assertTrue(request.correlationLabel().contains("execution=execution-1"))
    }

    @Test
    fun incompleteCorrelationIsRejected() {
        val request = TerminalExecutionRequest(
            command = "git status",
            context = context().copy(stepId = ""),
            executionId = "execution-1",
            actionId = "action-1",
            traceId = "trace-1",
            messageId = "message-1",
        )
        assertFalse(request.isStructurallyValid())
    }

    @Test
    fun rawSandboxExecutorCannotRunShellWithoutPermit() = runBlocking {
        val root = Files.createTempDirectory("airi-terminal-permit-").toFile()
        try {
            val result = SandboxExecutor(SandboxSession("test", "test", root)).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.SHELL_COMMAND,
                    command = "echo should-not-run",
                )
            )
            assertTrue(result is SandboxExecutor.ExecutionResult.SecurityViolation)
        } finally {
            root.deleteRecursively()
        }
    }
}
