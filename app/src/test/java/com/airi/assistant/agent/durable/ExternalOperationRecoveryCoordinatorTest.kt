package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOperationRecoveryCoordinatorTest {
    private fun waitingTask(
        operation: ExternalOperation = operation()
    ): DurableTask {
        val running = DurableTask(
            id = "task-external-recovery",
            title = "external recovery",
            description = "test",
            agentId = "agent",
            input = "test",
            plan = listOf(TaskPlanStep(id = "step-1", title = "remote job"))
        ).beginRun("run-1", "step-1", nowMs = 1_000L)
        return running.waitForExternalOperation(operation, nowMs = 1_500L)!!
    }

    private fun operation() = ExternalOperation(
        operationId = "operation-1",
        taskId = "task-external-recovery",
        runId = "run-1",
        stepId = "step-1",
        externalProvider = "fake-ci",
        externalReferenceId = "remote-42",
        startedAt = 1_200L,
        nextPollAt = 5_000L,
        timeoutPolicy = ExternalOperationTimeoutPolicy(deadlineAt = 20_000L),
        retryPolicy = ExternalOperationRetryPolicy(maxAttempts = 4)
    )

    @Test
    fun dueAndFutureOperationsScheduleOnlyStatusChecksWithExactDelay() {
        val future = waitingTask()
        val due = waitingTask(operation().copy(operationId = "operation-due", nextPollAt = 2_000L))

        val plan = ExternalOperationRecoveryCoordinator.plan(listOf(future, due), nowMs = 3_000L)

        assertEquals(
            listOf(
                ExternalOperationRecoveryAction.ScheduleStatusCheck(
                    taskId = future.id,
                    operationId = "operation-1",
                    runAtMs = 5_000L,
                    delayMs = 2_000L
                ),
                ExternalOperationRecoveryAction.ScheduleStatusCheck(
                    taskId = due.id,
                    operationId = "operation-due",
                    runAtMs = 2_000L,
                    delayMs = 0L
                )
            ),
            plan.actions
        )
    }

    @Test
    fun missingProviderReferenceAndMissingPollTimeRequireReconciliation() {
        val noReference = waitingTask(operation().copy(externalReferenceId = null))
        val noPollTime = waitingTask(operation().copy(nextPollAt = null))

        val plan = ExternalOperationRecoveryCoordinator.plan(listOf(noReference, noPollTime), nowMs = 3_000L)

        assertEquals(
            listOf(
                ExternalOperationRecoveryAction.NeedsReconciliation(
                    noReference.id,
                    "operation-1",
                    ExternalOperationRecoveryIssue.MISSING_PROVIDER_REFERENCE
                ),
                ExternalOperationRecoveryAction.NeedsReconciliation(
                    noPollTime.id,
                    "operation-1",
                    ExternalOperationRecoveryIssue.MISSING_DURABLE_POLL_TIME
                )
            ),
            plan.actions
        )
    }

    @Test
    fun expiredOrExhaustedOperationsAreNotAutomaticallyRescheduled() {
        val expired = waitingTask(
            operation().copy(timeoutPolicy = ExternalOperationTimeoutPolicy(deadlineAt = 3_000L))
        )
        val exhausted = waitingTask(operation().copy(pollAttemptCount = 4))

        val plan = ExternalOperationRecoveryCoordinator.plan(listOf(expired, exhausted), nowMs = 3_000L)

        assertEquals(
            listOf(
                ExternalOperationRecoveryAction.NeedsReconciliation(
                    expired.id,
                    "operation-1",
                    ExternalOperationRecoveryIssue.OPERATION_DEADLINE_REACHED
                ),
                ExternalOperationRecoveryAction.NeedsReconciliation(
                    exhausted.id,
                    "operation-1",
                    ExternalOperationRecoveryIssue.POLL_ATTEMPTS_EXHAUSTED
                )
            ),
            plan.actions
        )
    }

    @Test
    fun onlyWaitingTasksWithMatchingRunAndStepAreEligible() {
        val valid = waitingTask()
        val completed = valid.copy(status = DurableTaskStatus.READY_FOR_CONTINUATION)
        val invalidOwner = valid.copy(
            externalOperations = listOf(operation().copy(taskId = "another-task"))
        )
        val invalidRun = valid.copy(
            externalOperations = listOf(operation().copy(runId = "another-run"))
        )

        val plan = ExternalOperationRecoveryCoordinator.plan(
            listOf(completed, invalidOwner, invalidRun),
            nowMs = 3_000L
        )

        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.all { it is ExternalOperationRecoveryAction.NeedsReconciliation })
        assertTrue(
            plan.actions.filterIsInstance<ExternalOperationRecoveryAction.NeedsReconciliation>()
                .all { it.reason == ExternalOperationRecoveryIssue.OWNERSHIP_MISMATCH }
        )
    }
}
