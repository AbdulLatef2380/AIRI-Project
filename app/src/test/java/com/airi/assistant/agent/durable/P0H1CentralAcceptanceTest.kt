package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Central CRITICAL_P0 acceptance scenario.
 *
 * The fake store models the durable commit boundary. Re-opening the store is
 * represented by reading the last committed aggregate, while recovery is run
 * through the same pure coordinator used by the Android startup seam. No live
 * provider, wall-clock sleep, or Android process is needed for this contract.
 */
class P0H1CentralAcceptanceTest {
    @Test
    fun multiStepApprovalProcessDeathExternalQuotaAndContinuationCompleteSameTask() {
        val store = FakeTaskStore()
        val connector = FakeApprovalConnector()
        val originalTaskId = "p0-h1-task"
        val task = DurableTask(
            id = originalTaskId,
            missionId = originalTaskId,
            projectId = "project-p0",
            ownerId = "user-p0",
            sourceMessageId = 101L,
            title = "P0-H1 acceptance mission",
            description = "multi-stage acceptance fixture",
            agentId = "fake-agent",
            input = "complete the verified mission",
            plan = listOf(
                TaskPlanStep("collect", "Collect inputs"),
                TaskPlanStep("approved-write", "Apply approved change"),
                TaskPlanStep("external-ci", "Wait for external CI"),
                TaskPlanStep("verify", "Verify the objective"),
            ),
        )
        store.commit(task)

        // Run 1: make progress, then stop at an approval boundary.
        var current = store.read().beginRun("run-1", "collect", nowMs = 1_000L)
            .updateStep("collect", 25, "inputs collected", "checkpoint-collect", 1_100L)
            .completeStep("collect", 1_200L)
            .updateStep("approved-write", 35, "awaiting approval", "checkpoint-before-approval", 1_300L)

        val approval = TaskApproval(
            id = "approval-write",
            action = "write_file",
            description = "Apply the reviewed project change",
            riskLevel = "HIGH",
            requestedAtMs = 1_350L,
            expiresAtMs = 20_000L,
            runId = "run-1",
            stepId = "approved-write",
        )
        val continuation = ApprovalContinuation(
            id = "continuation-write",
            approvalId = approval.id,
            taskId = originalTaskId,
            missionId = originalTaskId,
            projectId = "project-p0",
            runId = "run-1",
            stepId = "approved-write",
            invocation = ResumableConnectorInvocation(
                connectorId = "fake-files",
                action = "write_file",
                params = mapOf("fileId" to "artifact-1"),
                idempotencyKey = "p0-h1-write-once",
            ),
            expiresAtMs = 20_000L,
        )
        current = current.requestApproval(approval)
            .pauseForApproval(approval.id, continuation, nowMs = 1_400L)
            ?: error("approval boundary must persist")
        store.commit(current)
        assertEquals(DurableTaskStatus.PAUSED, store.read().status)
        assertEquals(TaskPauseReason.APPROVAL, store.read().pauseReason)

        // Approval is granted exactly once and creates run 2 on the same task.
        current = store.read()
            .decideApproval(approval.id, TaskApprovalStatus.APPROVED, ApprovalGrantScope.ONCE, nowMs = 1_500L)
        val claimed = current.claimApprovedContinuation(
            approval.id,
            resumedRunId = "run-2",
            nowMs = 1_600L,
        ) ?: error("approved continuation must claim")
        current = claimed.first
        assertEquals(ApprovalContinuationStatus.CLAIMED, claimed.second.status)
        assertEquals(originalTaskId, current.id)
        assertEquals("run-2", current.currentRunId)
        assertEquals(null, current.claimApprovedContinuation(approval.id, nowMs = 1_601L))
        connector.execute(claimed.second)
        current = current.finishContinuation(
            continuationId = claimed.second.id,
            outcome = "write accepted",
            succeeded = true,
            nowMs = 1_650L,
        ) ?: error("claimed approval must finish")

        current = current
            .completeStep("approved-write", 1_700L)
            .updateStep("external-ci", 50, "CI submitted", "checkpoint-ci-submitted", 1_800L)

        // Run 2 yields after the fake provider accepts CI. No worker remains alive.
        val externalOperation = ExternalOperation(
            operationId = "ci-operation",
            taskId = originalTaskId,
            runId = "run-2",
            stepId = "external-ci",
            externalProvider = "fake-ci",
            externalReferenceId = "ci-42",
            startedAt = 1_850L,
            nextPollAt = 3_000L,
        )
        current = current.waitForExternalOperation(externalOperation, nowMs = 1_900L)
            ?: error("external operation must yield the run")
        store.commit(current)
        assertEquals(DurableTaskStatus.WAITING_EXTERNAL, store.read().status)
        assertEquals(TaskRunStatus.WAITING_EXTERNAL, store.read().runs.last().status)

        // Re-open while CI is still running: recovery must not enqueue a replay.
        val reopenedWhileExternalPending = store.reconcileAfterRestart(nowMs = 2_500L)
        assertEquals(DurableTaskStatus.WAITING_EXTERNAL, reopenedWhileExternalPending.status)
        assertEquals(2, reopenedWhileExternalPending.runs.size)

        // The delayed poll completes once and records a durable result reference.
        current = store.read().recordExternalOperationCheck(
            operationId = "ci-operation",
            externalStatus = ExternalOperationStatus.SUCCEEDED,
            resultReference = "artifact://ci-42",
            expectedNextPollAt = 3_000L,
            nowMs = 3_100L,
        ) ?: error("external result must be accepted")
        assertEquals(DurableTaskStatus.READY_FOR_CONTINUATION, current.status)
        assertEquals("artifact://ci-42", current.externalOperations.single().resultReference)

        // Run 3 consumes the external result. It is a new run, never a replay of run 2.
        current = current.resume("run-3", "analyze CI result", nowMs = 3_200L)
            .updateStep("verify", 65, "verification started", "checkpoint-verify-v1", 3_300L)
        store.commit(current)
        assertEquals(originalTaskId, current.id)
        assertEquals(3, current.runs.size)

        // Simulated process death during run 3. Recovery pauses conservatively and
        // a second reconciliation pass must be idempotent.
        current = store.reconcileAfterRestart(nowMs = 3_400L)
        assertEquals(DurableTaskStatus.PAUSED, current.status)
        assertEquals(TaskPauseReason.WORKER_INTERRUPTED, current.pauseReason)
        assertEquals("checkpoint-verify-v1", current.checkpointData)
        val secondRecovery = store.reconcileAfterRestart(nowMs = 3_500L)
        assertEquals(current, secondRecovery)
        assertEquals(3, secondRecovery.runs.size)

        // Resume after process death, then simulate provider quota exhaustion.
        current = secondRecovery.resume("run-4", "retry verification after recovery", nowMs = 3_600L)
            .updateStep("verify", 75, "provider quota reached", "checkpoint-quota", 3_700L)
        current = current.pauseForBudget(
            BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED,
            nowMs = 3_800L,
        ) ?: error("quota/budget exhaustion must pause non-terminally")
        store.commit(current)
        assertEquals(DurableTaskStatus.PAUSED_BUDGET, current.status)
        assertEquals(BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED, current.budgetPauseReason)
        assertFalse(current.isTerminal)

        // Quota is replenished explicitly; run 5 resumes the same task and finishes.
        current = store.read().resume("run-5", "quota replenished", nowMs = 4_000L)
            .updateStep("verify", 95, "objective verified", "checkpoint-final", 4_100L)
            .complete("P0-H1 objective verified", nowMs = 4_200L)
        store.commit(current)

        val completed = store.read()
        assertEquals(originalTaskId, completed.id)
        assertEquals("project-p0", completed.projectId)
        assertEquals(DurableTaskStatus.COMPLETED, completed.status)
        assertTrue(completed.isTerminal)
        assertEquals(5, completed.runs.size)
        assertEquals(TaskRunStatus.PAUSED, completed.runs[0].status)
        assertEquals(TaskRunStatus.WAITING_EXTERNAL, completed.runs[1].status)
        assertEquals(TaskRunStatus.PAUSED, completed.runs[2].status)
        assertEquals(TaskRunStatus.PAUSED, completed.runs[3].status)
        assertEquals(TaskRunStatus.COMPLETED, completed.runs[4].status)
        assertEquals(TaskStepStatus.COMPLETED, completed.plan.first { it.id == "collect" }.status)
        assertEquals(TaskStepStatus.COMPLETED, completed.plan.first { it.id == "approved-write" }.status)
        assertEquals(TaskStepStatus.COMPLETED, completed.plan.first { it.id == "external-ci" }.status)
        assertEquals(TaskStepStatus.COMPLETED, completed.plan.first { it.id == "verify" }.status)
        assertNotNull(completed.externalOperations.single().resultReference)
        assertTrue(completed.approvals.single().status == TaskApprovalStatus.APPROVED)
        assertEquals(1, connector.invocationCount)
        assertEquals(
            setOf(
                TaskTimelineEventType.TASK_BUDGET_PAUSED,
                TaskTimelineEventType.EXTERNAL_OPERATION_STARTED,
                TaskTimelineEventType.EXTERNAL_OPERATION_COMPLETED,
            ),
            completed.timeline.map { it.type }.toSet().intersect(
                setOf(
                    TaskTimelineEventType.TASK_BUDGET_PAUSED,
                    TaskTimelineEventType.EXTERNAL_OPERATION_STARTED,
                    TaskTimelineEventType.EXTERNAL_OPERATION_COMPLETED,
                )
            ),
        )
    }

    private class FakeTaskStore {
        private var committed: DurableTask? = null

        fun commit(task: DurableTask) {
            committed = task
        }

        fun read(): DurableTask = committed ?: error("task was not committed")

        fun reconcileAfterRestart(nowMs: Long): DurableTask {
            val task = read()
            val plan = DurableTaskRecoveryCoordinator.plan(listOf(task), nowMs)
            val recovered = plan.interruptedTasks.singleOrNull() ?: task
            commit(recovered)
            return recovered
        }
    }

    private class FakeApprovalConnector {
        var invocationCount: Int = 0
            private set

        fun execute(continuation: ApprovalContinuation) {
            check(continuation.status == ApprovalContinuationStatus.CLAIMED)
            invocationCount += 1
        }
    }
}
