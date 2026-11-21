package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContinuityCommandTest {
    private fun pausedTask(): DurableTask {
        val task = DurableTask(
            id = "task-a",
            title = "Paused task",
            description = "test",
            agentId = "agent",
            input = "continue",
            plan = listOf(TaskPlanStep("step-1", "step")),
        ).beginRun("run-1", "step-1", 1_000L)
        return task.pauseAfterWorkerStop("process death", 2_000L)
    }

    @Test
    fun newMissionNeverTargetsPausedTask() {
        val paused = pausedTask()
        val newTask = DurableTask(
            id = "task-b",
            title = "New task",
            description = "independent",
            agentId = "agent",
            input = "start new",
        )

        val decision = TaskContinuityCommandGate.decide(
            TaskContinuityCommand.NewMission(messageId = "message-b", task = newTask),
            mapOf(paused.id to paused),
        )

        assertTrue(decision is TaskContinuityDecision.CreateNewMission)
        assertEquals("task-b", (decision as TaskContinuityDecision.CreateNewMission).task.id)
    }

    @Test
    fun continueRequiresExactTaskAndExpectedVersion() {
        val paused = pausedTask()
        val stale = TaskContinuityCommandGate.decide(
            TaskContinuityCommand.ContinueExistingTask("message-a", paused.id, paused.stateVersion - 1),
            mapOf(paused.id to paused),
        )
        val accepted = TaskContinuityCommandGate.decide(
            TaskContinuityCommand.ContinueExistingTask("message-a", paused.id, paused.stateVersion, "resume"),
            mapOf(paused.id to paused),
        )

        assertEquals(RejectionReason.STALE_STATE_VERSION, (stale as TaskContinuityDecision.Rejected).reason)
        assertTrue(accepted is TaskContinuityDecision.ResumeExistingTask)
        assertEquals("task-a", (accepted as TaskContinuityDecision.ResumeExistingTask).task.id)
    }

    @Test
    fun unknownExternalOutcomeIsRejectedByTheCommandGate() {
        val running = DurableTask(
            id = "task-a",
            title = "External task",
            description = "test",
            agentId = "agent",
            input = "wait",
            plan = listOf(TaskPlanStep("step-1", "step")),
        ).beginRun("run-1", "step-1", 1_000L)
        val paused = running
            .waitForExternalOperation(
                ExternalOperation(
                    operationId = "op-1",
                    taskId = "task-a",
                    runId = "run-1",
                    stepId = "step-1",
                    externalProvider = "ci",
                    startedAt = 1_500L,
                    nextPollAt = 2_000L,
                ),
                1_600L,
            )!!
            .pauseForExternalOperationReconciliation("op-1", "lost", 2_000L)!!

        val decision = TaskContinuityCommandGate.decide(
            TaskContinuityCommand.ContinueExistingTask("message-a", paused.id, paused.stateVersion),
            mapOf(paused.id to paused),
        )

        assertEquals(RejectionReason.UNKNOWN_EXTERNAL_OUTCOME, (decision as TaskContinuityDecision.Rejected).reason)
    }
}
