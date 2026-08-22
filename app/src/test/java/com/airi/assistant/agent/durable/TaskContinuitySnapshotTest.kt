package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TaskContinuitySnapshotTest {

    @Test
    fun snapshotContainsProgressOnlyAndExcludesTaskContent() {
        val task = DurableTask(
            id = "task-1",
            projectId = "project-1",
            ownerId = "user-1",
            title = "Private task title",
            description = "Private task description",
            agentId = "research",
            input = "private input and secret token",
            checkpointData = "private checkpoint",
            result = "private result",
            status = DurableTaskStatus.PAUSED,
            updatedAtMs = 4_000L,
            currentRunId = "run-1",
            currentStepId = "step-1",
            progressPercent = 60,
            executionNode = "device-a",
            plan = listOf(
                TaskPlanStep(
                    id = "step-1",
                    title = "Private step title",
                    status = TaskStepStatus.RUNNING,
                    startedAtMs = 3_000L
                )
            )
        )

        val snapshot = TaskContinuitySnapshot.from(task)

        assertEquals("task-1", snapshot.taskId)
        assertEquals("project-1", snapshot.projectId)
        assertEquals(DurableTaskStatus.PAUSED, snapshot.status)
        assertEquals("step-1", snapshot.currentStepId)
        assertEquals(TaskStepStatus.RUNNING, snapshot.plan.single().status)
        assertFalse(snapshot.toString().contains("private input"))
        assertFalse(snapshot.toString().contains("private checkpoint"))
        assertFalse(snapshot.toString().contains("private result"))
        assertFalse(snapshot.toString().contains("Private task title"))
        assertFalse(snapshot.toString().contains("Private step title"))
    }

    @Test
    fun snapshotSchemaVersionIsStable() {
        val snapshot = TaskContinuitySnapshot.from(
            DurableTask(
                id = "task-2",
                title = "title",
                description = "description",
                agentId = "auto",
                input = "input"
            )
        )

        assertEquals(TaskContinuitySnapshot.SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(emptyList<TaskContinuitySnapshot.StepState>(), snapshot.plan)
    }
}
