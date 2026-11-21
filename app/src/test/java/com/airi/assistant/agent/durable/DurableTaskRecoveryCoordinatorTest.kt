package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskRecoveryCoordinatorTest {
    private fun task(id: String) = DurableTask(
        id = id,
        title = "Recovery fixture $id",
        description = "test",
        agentId = "agent",
        input = "test"
    )

    @Test
    fun interruptedRunIsPersistablePauseAndNeverAddedToGenericQueue() {
        val running = task("interrupted")
            .copy(plan = listOf(TaskPlanStep(id = "step-1", title = "Possible side effect")))
            .beginRun("run-1", "step-1", nowMs = 1_000L)
            .updateStep(
                stepId = "step-1",
                checkpointData = "checkpoint-before-recovery",
                progressPercent = 40,
                nowMs = 1_500L
            )

        val plan = DurableTaskRecoveryCoordinator.plan(listOf(running), nowMs = 2_000L)
        val paused = plan.interruptedTasks.single()

        assertEquals(DurableTaskStatus.PAUSED, paused.status)
        assertEquals(TaskPauseReason.WORKER_INTERRUPTED, paused.pauseReason)
        assertEquals("checkpoint-before-recovery", paused.checkpointData)
        assertEquals("run-1", paused.currentRunId)
        assertEquals(TaskRunStatus.PAUSED, paused.runs.single().status)
        assertEquals(TaskStepStatus.PAUSED, paused.plan.single().status)
        assertEquals(2_000L, paused.runs.single().finishedAtMs)
        assertFalse(paused.isTerminal)
        assertTrue(plan.queuedTaskIds.isEmpty())
        assertEquals(listOf("interrupted"), plan.interruptedTasks.map { it.id })
        assertEquals(TaskTimelineEventType.TASK_PAUSED, paused.timeline.last().type)

        val secondPass = DurableTaskRecoveryCoordinator.plan(plan.interruptedTasks, nowMs = 3_000L)
        assertTrue(secondPass.interruptedTasks.isEmpty())
        assertTrue(secondPass.queuedTaskIds.isEmpty())
    }

    @Test
    fun onlyQueuedWorkIsEligibleAndExternalWaitsStayOutOfGenericWorkerQueue() {
        val queued = task("queued")
        val running = task("external")
            .copy(plan = listOf(TaskPlanStep(id = "step-1", title = "Build")))
            .beginRun("run-1", "step-1", nowMs = 1_000L)
        val externalWait = running.waitForExternalOperation(
            ExternalOperation(
                operationId = "op-1",
                taskId = "external",
                runId = "run-1",
                stepId = "step-1",
                externalProvider = "ci",
                externalReferenceId = "build-42",
                startedAt = 1_100L,
                nextPollAt = 5_000L
            ),
            nowMs = 1_200L
        )!!
        val continuationReady = externalWait.recordExternalOperationCheck(
            operationId = "op-1",
            externalStatus = ExternalOperationStatus.SUCCEEDED,
            resultReference = "artifact://build-42",
            expectedNextPollAt = 5_000L,
            nowMs = 5_100L
        )!!
        val completed = task("completed").beginRun("done-run", nowMs = 1_000L)
            .complete("done", nowMs = 2_000L)

        val plan = DurableTaskRecoveryCoordinator.plan(
            listOf(queued, externalWait, continuationReady, completed),
            nowMs = 6_000L
        )

        assertEquals(listOf("queued"), plan.queuedTaskIds)
        assertEquals(listOf("external"), plan.waitingExternalTaskIds)
        assertTrue(plan.interruptedTasks.isEmpty())
        assertEquals(
            setOf("queued", "external", continuationReady.id, "completed"),
            plan.untouchedTaskIds.toSet()
        )
        assertEquals(DurableTaskStatus.WAITING_EXTERNAL, externalWait.status)
        assertEquals(DurableTaskStatus.READY_FOR_CONTINUATION, continuationReady.status)
        assertEquals(DurableTaskStatus.COMPLETED, completed.status)
    }

    @Test
    fun delayedExternalOperationIsNotMistakenForQueuedTask() {
        val waiting = task("timer")
            .copy(plan = listOf(TaskPlanStep(id = "step-1", title = "External job")))
            .beginRun("run-1", "step-1", nowMs = 1_000L)
            .waitForExternalOperation(
                ExternalOperation(
                    operationId = "op-timer",
                    taskId = "timer",
                    runId = "run-1",
                    stepId = "step-1",
                    externalProvider = "ci",
                    startedAt = 1_100L,
                    nextPollAt = 10_000L
                ),
                nowMs = 1_200L
            )!!
            .recordExternalOperationCheck(
                operationId = "op-timer",
                externalStatus = ExternalOperationStatus.RUNNING,
                nextPollAt = 20_000L,
                expectedNextPollAt = 10_000L,
                nowMs = 10_000L
            )!!

        val plan = DurableTaskRecoveryCoordinator.plan(listOf(waiting), nowMs = 11_000L)

        assertEquals(DurableTaskStatus.WAITING_TIMER, waiting.status)
        assertTrue(plan.queuedTaskIds.isEmpty())
        assertEquals(listOf("timer"), plan.waitingExternalTaskIds)
    }

    @Test
    fun recoveryPersistsInterruptedRunBeforeSchedulingAndRepeatedKeepEnqueueIsIdempotent() {
        val interrupted = task("running").beginRun("run-1", nowMs = 1_000L)
        val queued = task("queued")
        val persisted = mutableMapOf(interrupted.id to interrupted, queued.id to queued)
        val operations = mutableListOf<String>()
        val acceptedUniqueWork = mutableSetOf<String>()
        val runner = DurableTaskRecoveryRunner(
            persistInterrupted = { recovered ->
                persisted[recovered.id] = recovered
                operations += "persist:${recovered.id}"
            },
            scheduleQueued = { candidate ->
                assertEquals(DurableTaskStatus.PAUSED, persisted[interrupted.id]?.status)
                operations += "enqueue:${candidate.id}"
                acceptedUniqueWork.add(candidate.id) // Models WorkManager ExistingWorkPolicy.KEEP.
            },
        )

        val first = runner.reconcile(persisted.values.toList(), nowMs = 2_000L)
        val second = runner.reconcile(persisted.values.toList(), nowMs = 3_000L)

        assertEquals(listOf("persist:running", "enqueue:queued", "enqueue:queued"), operations)
        assertEquals(DurableTaskStatus.PAUSED, persisted.getValue("running").status)
        assertEquals(listOf("running"), first.pausedInterruptedTaskIds)
        assertTrue(second.pausedInterruptedTaskIds.isEmpty())
        assertEquals(listOf("queued"), first.scheduledQueuedTaskIds)
        assertEquals(listOf("queued"), second.scheduledQueuedTaskIds)
        assertEquals(setOf("queued"), acceptedUniqueWork)
    }

    @Test
    fun failedQueueSubmissionLeavesTaskQueuedForAnotherRecoveryPass() {
        val queued = task("retry-schedule")
        var shouldFail = true
        val runner = DurableTaskRecoveryRunner(
            persistInterrupted = {},
            scheduleQueued = {
                if (shouldFail) error("simulated WorkManager enqueue failure")
            },
        )

        val failed = runner.reconcile(listOf(queued), nowMs = 1_000L)
        shouldFail = false
        val recovered = runner.reconcile(listOf(queued), nowMs = 2_000L)

        assertTrue(failed.scheduledQueuedTaskIds.isEmpty())
        assertEquals(listOf("retry-schedule"), failed.eligibleQueuedTaskIds)
        assertEquals(listOf("retry-schedule"), recovered.scheduledQueuedTaskIds)
        assertEquals(DurableTaskStatus.QUEUED, queued.status)
    }

    @Test
    fun failedInterruptedStateCommitAbortsBeforeAnyQueuedWorkIsScheduled() {
        val running = task("running").beginRun("run-1", nowMs = 1_000L)
        val queued = task("queued")
        var scheduled = false
        val runner = DurableTaskRecoveryRunner(
            persistInterrupted = { error("simulated database commit failure") },
            scheduleQueued = { scheduled = true },
        )

        val result = runCatching { runner.reconcile(listOf(running, queued), nowMs = 2_000L) }

        assertTrue(result.isFailure)
        assertFalse(scheduled)
    }
}
