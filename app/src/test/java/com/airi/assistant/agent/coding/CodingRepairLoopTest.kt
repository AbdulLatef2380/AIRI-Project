package com.airi.assistant.agent.coding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingRepairLoopTest {
    private fun task(id: String, vararg tools: String) = CodingTask(
        taskId = id,
        principal = "coding-agent",
        sessionId = "workspace-1",
        generationId = 1L,
        actions = tools.mapIndexed { index, tool ->
            CodingAction(
                stepId = "$id-step-$index",
                toolId = tool,
                arguments = when (tool) {
                    "apply_patch" -> mapOf("path" to "/main.py", "patch" to "@@ -1 +1 @@")
                    "run_tests" -> mapOf("command" to "python -m pytest")
                    else -> emptyMap()
                },
            )
        },
    )

    private fun report(
        task: CodingTask,
        status: CodingAgentStatus,
        failedTool: String? = null,
        evidence: String = "",
    ): CodingAgentExecutionReport = CodingAgentExecutionReport(
        taskId = task.taskId,
        status = status,
        steps = task.actions.map { action ->
            CodingStepEvidence(
                stepId = action.stepId,
                toolId = action.toolId,
                status = if (action.toolId == failedTool) "FAILED" else "SUCCEEDED",
                evidence = if (action.toolId == failedTool || action.toolId == "apply_patch" || action.toolId == "run_tests") evidence else "",
            )
        },
        remainingStepIds = emptyList(),
    )

    @Test
    fun failedVerificationIsReplannedWithPatchAndFinalVerification() = kotlinx.coroutines.runBlocking {
        val initial = task("initial", "apply_patch", "run_tests")
        val repair = task("repair-1", "apply_patch", "run_tests")
        val seen = mutableListOf<String>()
        val loop = CodingRepairLoop(
            runner = CodingTaskRunner { current ->
                seen += current.taskId
                if (current.taskId == initial.taskId) {
                    report(initial, CodingAgentStatus.FAILED, failedTool = "run_tests", evidence = "2 tests failed")
                } else {
                    report(repair, CodingAgentStatus.SUCCEEDED, evidence = "diff applied; 2 tests passed")
                }
            },
            planner = CodingRepairPlanner { request ->
                assertEquals(1, request.attempt)
                assertEquals("run_tests", request.failedReport.steps.last().toolId)
                repair
            },
        )

        val result = loop.execute(initial)

        assertEquals(CodingRepairStatus.SUCCEEDED, result.status)
        assertEquals(listOf("initial", "repair-1"), seen)
        assertTrue(result.attempts.any { it.diffEvidence.contains("diff applied") })
        assertTrue(result.attempts.any { it.verificationEvidence.contains("run_tests:SUCCEEDED") })
    }

    @Test
    fun repairPlanWithoutPatchOrVerificationIsBlockedBeforeExecution() = kotlinx.coroutines.runBlocking {
        val initial = task("initial", "run_tests")
        val invalidRepair = task("invalid", "write_file")
        var calls = 0
        val result = CodingRepairLoop(
            runner = CodingTaskRunner { current ->
                calls++
                report(current, CodingAgentStatus.FAILED, failedTool = "run_tests", evidence = "failed")
            },
            planner = CodingRepairPlanner { invalidRepair },
        ).execute(initial)

        assertEquals(CodingRepairStatus.BLOCKED, result.status)
        assertEquals("repair_plan_missing_patch_or_verification", result.terminalReason)
        assertEquals(1, calls)
    }

    @Test
    fun nonVerificationFailureStopsWithoutCallingPlanner() = kotlinx.coroutines.runBlocking {
        val initial = task("initial", "apply_patch")
        var plannerCalls = 0
        val result = CodingRepairLoop(
            runner = CodingTaskRunner { current ->
                report(current, CodingAgentStatus.FAILED, failedTool = "apply_patch", evidence = "patch rejected")
            },
            planner = CodingRepairPlanner {
                plannerCalls++
                null
            },
        ).execute(initial)

        assertEquals(CodingRepairStatus.FAILED, result.status)
        assertEquals(0, plannerCalls)
        assertEquals("failure_not_repairable", result.terminalReason)
    }

    @Test
    fun repairAttemptsAreBounded() = kotlinx.coroutines.runBlocking {
        val initial = task("initial", "run_tests")
        val repairs = listOf(
            task("repair-1", "apply_patch", "run_tests"),
            task("repair-2", "apply_patch", "run_tests"),
            task("repair-3", "apply_patch", "run_tests"),
        )
        var calls = 0
        var plannerCalls = 0
        val result = CodingRepairLoop(
            runner = CodingTaskRunner { current ->
                calls++
                report(current, CodingAgentStatus.FAILED, failedTool = "run_tests", evidence = "still failing")
            },
            planner = CodingRepairPlanner { repairs[plannerCalls++] },
            maxAttempts = 2,
        ).execute(initial)

        assertEquals(CodingRepairStatus.EXHAUSTED, result.status)
        assertEquals(3, calls)
        assertEquals(2, plannerCalls)
        assertTrue(result.attempts.size <= 3)
    }
}
