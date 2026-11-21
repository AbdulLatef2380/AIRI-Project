package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableTaskExecutionBoundaryTest {
    private fun queuedTask() = DurableTask(
        id = "boundary-task",
        title = "Boundary fixture",
        description = "test",
        agentId = "agent",
        input = "opaque task input",
        plan = listOf(
            TaskPlanStep(id = "step-1", title = "First step"),
            TaskPlanStep(id = "step-2", title = "Second step"),
        ),
    )

    @Test
    fun claimsOnlyTheFirstPendingStepAndCarriesCheckpoint() {
        val task = queuedTask().copy(
            currentStepId = "step-2",
            plan = listOf(
                TaskPlanStep(id = "step-1", title = "Done", status = TaskStepStatus.COMPLETED),
                TaskPlanStep(id = "step-2", title = "Next", status = TaskStepStatus.PAUSED),
            ),
            checkpointData = "checkpoint-before-step-2",
        )

        val lease = DurableTaskExecutionBoundary.claim(task, "run-2")

        assertEquals("boundary-task", lease.taskId)
        assertEquals("run-2", lease.runId)
        assertEquals("step-2", lease.stepId)
        assertEquals("checkpoint-before-step-2", lease.checkpointData)
    }

    @Test
    fun rejectsCompletedStepInsteadOfReplayingIt() {
        val task = queuedTask().copy(
            currentStepId = "step-1",
            plan = listOf(TaskPlanStep(id = "step-1", title = "Done", status = TaskStepStatus.COMPLETED)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DurableTaskExecutionBoundary.claim(task, "run-1")
        }
    }

    @Test
    fun rejectsClaimedOrUnknownOperationUntilReconciliation() {
        listOf(DurableOperationStatus.CLAIMED, DurableOperationStatus.UNKNOWN).forEach { status ->
            val task = queuedTask().copy(
                operations = listOf(
                    DurableOperation(
                        operationId = "op-1",
                        taskId = "boundary-task",
                        runId = "run-1",
                        stepId = "step-1",
                        actionId = "write",
                        idempotencyKey = "boundary-task:run-1:write",
                        payloadSha256 = "sha",
                        provider = "fake",
                        status = status,
                    )
                )
            )

            assertThrows(IllegalArgumentException::class.java) {
                DurableTaskExecutionBoundary.claim(task, "run-1")
            }
        }
    }
}
