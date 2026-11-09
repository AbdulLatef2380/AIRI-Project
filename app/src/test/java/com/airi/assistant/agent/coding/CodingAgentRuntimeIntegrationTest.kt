package com.airi.assistant.agent.coding

import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.domain.tool.ArgumentSpec
import com.airi.assistant.domain.tool.AuthorizationDecision
import com.airi.assistant.domain.tool.ToolExecutionPipeline
import com.airi.assistant.domain.tool.ToolHandler
import com.airi.assistant.domain.tool.ToolHandlerResult
import com.airi.assistant.domain.tool.ToolSandbox
import com.airi.assistant.domain.tool.ToolSpec
import com.airi.assistant.domain.tool.ToolSpecRegistry
import com.airi.assistant.domain.tool.ToolAuthorizer
import com.airi.assistant.domain.tool.ToolRateLimiter
import com.airi.assistant.domain.tool.ApprovalTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CodingAgentRuntimeIntegrationTest {

    @Test
    fun productionPathWritesRepairsAndVerifiesThroughOnePipeline() = runBlocking {
        val calls = mutableListOf<String>()
        val testRuns = AtomicInteger(0)
        val context = SubAgentContext(
            sessionId = "session-1",
            userId = "user-1",
            parentTaskId = "execution-1",
            executionId = "execution-1",
        )
        val initial = CodingTask(
            taskId = "initial-task",
            principal = "coding-agent",
            sessionId = "workspace-1",
            generationId = 1L,
            actions = listOf(
                CodingAction("write", "write_file", mapOf("path" to "/workspace/main.kt", "content" to "fun main() = 1")),
                CodingAction("verify-1", "run_tests", verificationArgs()),
            ),
        )
        val repair = initial.copy(
            taskId = "repair-task",
            actions = listOf(
                CodingAction("patch", "apply_patch", mapOf("path" to "/workspace/main.kt", "patch" to "@@ fix")),
                CodingAction("verify-2", "run_tests", verificationArgs()),
            ),
        )
        val runtime = CodingAgentRuntime(
            planner = CodingTaskPlanner { _, _ ->
                if (testRuns.get() == 0) initial else repair
            },
            pipeline = pipeline { invocation ->
                calls += invocation.request.toolId
                when (invocation.request.toolId) {
                    "run_tests" -> if (testRuns.incrementAndGet() == 1) {
                        ToolHandlerResult.Failure("exit_code=1 tests_failed api_key=hidden")
                    } else {
                        ToolHandlerResult.Success("exit_code=0 tests_passed")
                    }
                    "write_file" -> ToolHandlerResult.Success("file_written api_key=hidden")
                    "apply_patch" -> ToolHandlerResult.Success("diff_applied")
                    else -> ToolHandlerResult.Failure("unexpected_tool")
                }
            },
        )

        val result = runtime.execute("Fix the failing Kotlin tests", context)

        assertEquals(CodingRepairStatus.SUCCEEDED, result.report.status)
        assertEquals(listOf("write_file", "run_tests", "apply_patch", "run_tests"), calls)
        assertEquals("repair-task", result.report.finalTaskId)
        assertTrue(result.evidence.contains("final_step=verify-2 tool=run_tests status=SUCCEEDED"))
        assertFalse(result.evidence.contains("api_key=hidden"))
        assertTrue(result.report.finalExecution.steps.any { it.toolId == "apply_patch" })
    }

    @Test
    fun boundaryRejectsOutsideWorkspaceBeforePipelineAndLeavesLaterStepsUnexecuted() = runBlocking {
        var pipelineCalls = 0
        val runtime = CodingAgentRuntime(
            planner = CodingTaskPlanner { _, _ ->
                CodingTask(
                    taskId = "unsafe-task",
                    principal = "coding-agent",
                    sessionId = "workspace-unsafe",
                    generationId = 1L,
                    actions = listOf(
                        CodingAction("escape", "write_file", mapOf("path" to "../secret", "content" to "no")),
                        CodingAction("never", "run_tests", verificationArgs()),
                    ),
                )
            },
            pipeline = pipeline {
                pipelineCalls++
                ToolHandlerResult.Success("unexpected")
            },
        )

        val result = runtime.execute("Write the file", SubAgentContext.test())

        assertEquals(CodingRepairStatus.FAILED, result.report.status)
        assertEquals("path_traversal", result.report.finalExecution.steps.single().evidence)
        assertEquals(0, pipelineCalls)
        assertEquals(listOf("never"), result.report.finalExecution.remainingStepIds)
    }

    @Test
    fun malformedVerificationCommandIsRejectedBeforeExecution() = runBlocking {
        var pipelineCalls = 0
        val runtime = CodingAgentRuntime(
            planner = CodingTaskPlanner { _, _ ->
                CodingTask(
                    taskId = "bad-command",
                    principal = "coding-agent",
                    sessionId = "workspace-command",
                    generationId = 1L,
                    actions = listOf(
                        CodingAction("verify", "run_tests", mapOf(
                            "command" to "python -m pytest",
                            "working_directory" to "/workspace",
                        )),
                    ),
                )
            },
            pipeline = pipeline {
                pipelineCalls++
                ToolHandlerResult.Success("unexpected")
            },
        )

        val result = runtime.execute("Run tests", SubAgentContext.test())

        assertEquals(CodingRepairStatus.FAILED, result.report.status)
        assertEquals("verification_command_not_allowlisted", result.report.finalExecution.steps.single().evidence)
        assertEquals(0, pipelineCalls)
    }

    @Test
    fun cancellationPropagatesAndDoesNotRunFollowingActions() = runBlocking {
        val calls = mutableListOf<String>()
        val runtime = CodingAgentRuntime(
            planner = CodingTaskPlanner { _, _ ->
                CodingTask(
                    taskId = "cancel-task",
                    principal = "coding-agent",
                    sessionId = "workspace-cancel",
                    generationId = 1L,
                    actions = listOf(
                        CodingAction("write", "write_file", mapOf("path" to "/workspace/a.kt", "content" to "x")),
                        CodingAction("verify", "run_tests", verificationArgs()),
                    ),
                )
            },
            pipeline = pipeline { invocation ->
                calls += invocation.request.toolId
                throw CancellationException("cancelled")
            },
        )

        var cancelled = false
        try {
            runtime.execute("Cancel coding task", SubAgentContext.test())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("write_file"), calls)
    }

    private fun verificationArgs() = mapOf(
        "command" to "./gradlew testDebugUnitTest",
        "working_directory" to "/workspace",
        "output_chars" to "16000",
    )

    private fun pipeline(
        handlerBlock: suspend (com.airi.assistant.domain.tool.ValidatedAuthorizedInvocation) -> ToolHandlerResult,
    ): ToolExecutionPipeline {
        val specs = object : ToolSpecRegistry {
            private val all = listOf(
                spec("write_file", "path", "content"),
                spec("apply_patch", "path", "patch"),
                spec("run_tests", "command", "working_directory", "output_chars"),
            ).associateBy { it.id }
            override fun find(toolId: String): ToolSpec? = all[toolId]
        }
        return ToolExecutionPipeline(
            specs = specs,
            authorizer = ToolAuthorizer { AuthorizationDecision.Allow },
            approvalTokens = ApprovalTokenStore { _, _ -> true },
            rateLimiter = ToolRateLimiter { _, _ -> true },
            sandbox = object : ToolSandbox {
                override suspend fun <T> execute(
                    invocation: com.airi.assistant.domain.tool.ValidatedAuthorizedInvocation,
                    block: suspend () -> T,
                ): T = block()
            },
            handler = ToolHandler { invocation -> handlerBlock(invocation) },
        )
    }

    private fun spec(id: String, vararg names: String) = ToolSpec(
        id = id,
        arguments = names.map { ArgumentSpec(it, ArgumentSpec.Type.STRING, required = true) },
        capability = "coding",
        risk = ToolSpec.Risk.SIDE_EFFECT,
        privacy = ToolSpec.PrivacyBoundary.LOCAL_ONLY,
        requiresConfirmation = false,
        idempotent = id == "run_tests",
        handlerKey = id,
    )
}
