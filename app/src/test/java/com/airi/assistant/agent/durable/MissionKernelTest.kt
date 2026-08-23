package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionKernelTest {

    @Test
    fun normalizeAssignsMissionAndOwnershipAcrossRunStepAndApproval() {
        val normalized = MissionKernel.normalize(
            DurableTask(
                id = "task-a",
                projectId = "project-a",
                title = "Generate report",
                description = "Generate report",
                agentId = "research",
                input = "Generate report",
                currentRunId = "run-a",
                currentStepId = "step-a",
                plan = listOf(TaskPlanStep(id = "step-a", title = "Write")),
                runs = listOf(TaskRun(id = "run-a", startedAtMs = 10L, status = TaskRunStatus.RUNNING)),
                approvals = listOf(
                    TaskApproval(
                        id = "approval-a",
                        action = "write_file",
                        description = "Write report",
                        riskLevel = "HIGH",
                        expiresAtMs = 100L
                    )
                )
            )
        )

        assertEquals("task-a", normalized.missionId)
        assertEquals("task-a", normalized.runs.single().taskId)
        assertEquals("task-a", normalized.runs.single().missionId)
        assertEquals("project-a", normalized.runs.single().projectId)
        assertEquals("run-a", normalized.plan.single().runId)
        assertEquals("task-a", normalized.approvals.single().taskId)
        assertEquals("task-a", normalized.approvals.single().missionId)
        assertEquals("project-a", normalized.approvals.single().projectId)
        assertTrue(MissionKernel.validate(normalized) is MissionOwnershipValidation.Valid)
    }

    @Test
    fun validateRejectsCrossProjectRunAndApproval() {
        val task = MissionKernel.normalize(
            DurableTask(
                id = "task-b",
                projectId = "project-b",
                missionId = "mission-b",
                title = "Inspect project",
                description = "Inspect project",
                agentId = "agent",
                input = "Inspect",
                runs = listOf(
                    TaskRun(
                        id = "run-b",
                        taskId = "task-b",
                        missionId = "mission-b",
                        projectId = "project-a",
                        startedAtMs = 10L,
                        status = TaskRunStatus.RUNNING
                    )
                )
            )
        )

        assertTrue(MissionKernel.validate(task) is MissionOwnershipValidation.Invalid)
    }

    @Test
    fun projectAccessRequiresExactProjectOwnership() {
        val task = DurableTask(
            id = "task-c",
            projectId = "project-c",
            title = "Scoped task",
            description = "Scoped task",
            agentId = "agent",
            input = "Scoped"
        )

        assertTrue(MissionKernel.canAccessProject(task, "project-c"))
        assertFalse(MissionKernel.canAccessProject(task, "project-other"))
        assertFalse(MissionKernel.canAccessProject(task, null))
    }
}
