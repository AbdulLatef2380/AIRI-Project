package com.airi.assistant.agent.durable

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskLifecycleContractTest {
    private val gson = Gson()

    private fun runningTask(): DurableTask = DurableTask(
        id = "task-lifecycle",
        title = "Lifecycle fixture",
        description = "test",
        agentId = "agent",
        input = "test",
        plan = listOf(TaskPlanStep(id = "step-1", title = "Run external job"))
    ).beginRun("run-1", "step-1", nowMs = 1_000L)

    @Test
    fun budgetPauseIsTypedNonTerminalAndCannotResumeWithoutPolicyTransition() {
        val paused = runningTask().pauseForBudget(
            BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED,
            nowMs = 2_000L
        )!!

        assertEquals(DurableTaskStatus.PAUSED_BUDGET, paused.status)
        assertEquals(BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED, paused.budgetPauseReason)
        assertFalse(paused.isTerminal)
        assertEquals(TaskRunStatus.PAUSED, paused.runs.single().status)
        assertEquals(2_000L, paused.runs.single().finishedAtMs)
        assertTrue(runCatching { paused.beginRun("run-2", "step-1", nowMs = 3_000L) }.isFailure)
        assertNull(paused.pauseForBudget(BudgetPauseReason.RUN_BUDGET_EXHAUSTED, 4_000L))
    }

    @Test
    fun explicitResumeCreatesExactlyOneFreshRunAndPreservesCheckpoint() {
        val paused = runningTask()
            .updateStep("step-1", checkpointData = "checkpoint-v1", nowMs = 1_500L)
            .pauseAfterWorkerStop("process-death", nowMs = 2_000L)

        val resumed = paused.resume(
            runId = "run-2",
            userInstruction = "continue from the checkpoint",
            nowMs = 3_000L
        )

        assertEquals(DurableTaskStatus.RUNNING, resumed.status)
        assertEquals("checkpoint-v1", resumed.checkpointData)
        assertEquals("run-2", resumed.currentRunId)
        assertEquals(2, resumed.runs.size)
        assertEquals(TaskRunStatus.PAUSED, resumed.runs.first().status)
        assertEquals(TaskRunStatus.RUNNING, resumed.runs.last().status)
        assertEquals("continue from the checkpoint", resumed.continuationInstruction)
        assertTrue(runCatching { resumed.resume("run-3", nowMs = 4_000L) }.isFailure)
    }

    @Test
    fun unknownExternalOutcomeCannotBeResumedAutomatically() {
        val waiting = runningTask().waitForExternalOperation(
            ExternalOperation(
                operationId = "op-unknown",
                taskId = "task-lifecycle",
                runId = "run-1",
                stepId = "step-1",
                externalProvider = "ci-provider",
                externalReferenceId = "job-unknown",
                startedAt = 1_500L,
                nextPollAt = 2_000L
            ),
            nowMs = 1_600L
        )!!
        val paused = waiting.pauseForExternalOperationReconciliation(
            operationId = "op-unknown",
            reason = "REMOTE_RESULT_UNAVAILABLE",
            nowMs = 2_500L
        )!!

        assertTrue(runCatching { paused.resume("run-2", nowMs = 3_000L) }.isFailure)
    }

    @Test
    fun operationLedgerRequiresPrepareThenSingleClaimAndPreservesUnknown() {
        val task = runningTask()
        val operation = DurableOperation(
            operationId = "operation-1",
            taskId = task.id,
            runId = "run-1",
            stepId = "step-1",
            actionId = "action-1",
            idempotencyKey = "task-lifecycle:run-1:action-1",
            payloadSha256 = "sha256-payload",
            provider = "calendar",
        )

        val prepared = task.prepareOperation(operation, nowMs = 2_000L)!!
        assertEquals(DurableOperationStatus.PREPARED, prepared.operations.single().status)
        assertTrue(prepared.prepareOperation(operation, nowMs = 2_100L) == null)

        val claimed = prepared.claimOperation("operation-1", nowMs = 2_200L)!!
        assertEquals(DurableOperationStatus.CLAIMED, claimed.operations.single().status)
        assertTrue(claimed.claimOperation("operation-1", nowMs = 2_300L) == null)

        val unknown = claimed.completeOperation(
            operationId = "operation-1",
            status = DurableOperationStatus.UNKNOWN,
            safeError = "response lost after provider accepted request",
            nowMs = 2_400L,
        )!!
        assertEquals(DurableOperationStatus.UNKNOWN, unknown.operations.single().status)
        assertTrue(unknown.operations.single().safeError.contains("response lost"))
        assertTrue(unknown.completeOperation("operation-1", DurableOperationStatus.SUCCEEDED, nowMs = 2_500L) == null)
    }

    @Test
    fun externalOperationYieldsRunWaitsDurablyThenRequiresSafeContinuation() {
        val operation = ExternalOperation(
            operationId = "op-1",
            taskId = "task-lifecycle",
            runId = "run-1",
            stepId = "step-1",
            externalProvider = "ci-provider",
            externalReferenceId = "job-42",
            startedAt = 1_500L,
            nextPollAt = 2_000L,
            timeoutPolicy = ExternalOperationTimeoutPolicy(deadlineAt = 50_000L),
            retryPolicy = ExternalOperationRetryPolicy(maxAttempts = 12)
        )
        val waiting = runningTask().waitForExternalOperation(operation, nowMs = 1_600L)!!

        assertEquals(DurableTaskStatus.WAITING_EXTERNAL, waiting.status)
        assertFalse(waiting.isTerminal)
        assertEquals(TaskRunStatus.WAITING_EXTERNAL, waiting.runs.single().status)
        assertEquals(1_600L, waiting.runs.single().finishedAtMs)
        assertEquals(operation, waiting.externalOperations.single())
        assertNull(waiting.waitForExternalOperation(operation, nowMs = 1_700L))

        assertNull(
            waiting.recordExternalOperationCheck(
                operationId = "op-1",
                externalStatus = ExternalOperationStatus.RUNNING,
                nextPollAt = 4_000L,
                expectedNextPollAt = 1_900L,
                nowMs = 2_000L
            )
        )

        val delayed = waiting.recordExternalOperationCheck(
            operationId = "op-1",
            externalStatus = ExternalOperationStatus.RUNNING,
            nextPollAt = 5_000L,
            expectedNextPollAt = 2_000L,
            nowMs = 2_000L
        )!!
        assertEquals(DurableTaskStatus.WAITING_TIMER, delayed.status)
        assertEquals(5_000L, delayed.externalOperations.single().nextPollAt)
        assertEquals(2_000L, delayed.externalOperations.single().lastCheckedAt)
        assertEquals(1, delayed.externalOperations.single().pollAttemptCount)
        assertEquals(TaskRunStatus.WAITING_EXTERNAL, delayed.runs.single().status)
        assertEquals(TaskStepStatus.WAITING_TIMER, delayed.plan.single().status)
        assertNull(
            delayed.recordExternalOperationCheck(
                operationId = "op-1",
                externalStatus = ExternalOperationStatus.SUCCEEDED,
                resultReference = "stale-result",
                expectedNextPollAt = 2_000L,
                nowMs = 4_000L
            )
        )
        assertEquals(ExternalOperationStatus.RUNNING, delayed.externalOperations.single().externalStatus)
        assertEquals(5_000L, delayed.externalOperations.single().nextPollAt)

        val ready = delayed.recordExternalOperationCheck(
            operationId = "op-1",
            externalStatus = ExternalOperationStatus.SUCCEEDED,
            resultReference = "artifact://ci/job-42",
            expectedNextPollAt = 5_000L,
            nowMs = 5_100L
        )!!
        assertEquals(DurableTaskStatus.READY_FOR_CONTINUATION, ready.status)
        assertFalse(ready.isTerminal)
        assertEquals("run-1", ready.currentRunId)
        assertEquals(TaskRunStatus.WAITING_EXTERNAL, ready.runs.single().status)
        assertEquals(TaskStepStatus.COMPLETED, ready.plan.single().status)
        assertEquals("artifact://ci/job-42", ready.externalOperations.single().resultReference)
        assertNull(ready.externalOperations.single().nextPollAt)
        assertNull(
            ready.recordExternalOperationCheck(
                operationId = "op-1",
                externalStatus = ExternalOperationStatus.FAILED,
                expectedNextPollAt = null,
                nowMs = 6_000L
            )
        )
        assertTrue(runCatching { ready.beginRun("run-2", "step-1", nowMs = 7_000L) }.isFailure)
    }

    @Test
    fun externalOperationMustMatchCurrentTaskRunAndStep() {
        val mismatched = ExternalOperation(
            operationId = "op-invalid",
            taskId = "another-task",
            runId = "run-1",
            stepId = "step-1",
            externalProvider = "ci-provider",
            startedAt = 1_500L
        )
        assertNull(runningTask().waitForExternalOperation(mismatched, nowMs = 1_600L))

        val ambiguousStatus = runningTask().waitForExternalOperation(
            mismatched.copy(taskId = "task-lifecycle"),
            nowMs = 1_600L
        )!!.recordExternalOperationCheck(
            operationId = "op-invalid",
            externalStatus = ExternalOperationStatus.UNKNOWN,
            nextPollAt = 3_000L,
            expectedNextPollAt = null,
            nowMs = 2_000L
        )!!
        assertEquals(DurableTaskStatus.WAITING_TIMER, ambiguousStatus.status)
        assertFalse(ambiguousStatus.externalOperations.single().externalStatus.isTerminal)
    }

    @Test
    fun exhaustedExternalWaitPausesWithUnknownOutcomeAndNeverQueuesReplay() {
        val waiting = runningTask().waitForExternalOperation(
            ExternalOperation(
                operationId = "op-pause",
                taskId = "task-lifecycle",
                runId = "run-1",
                stepId = "step-1",
                externalProvider = "ci-provider",
                externalReferenceId = "job-43",
                startedAt = 1_500L,
                nextPollAt = 2_000L
            ),
            nowMs = 1_600L
        )!!

        val paused = waiting.pauseForExternalOperationReconciliation(
            operationId = "op-pause",
            reason = "POLL_ATTEMPTS_EXHAUSTED",
            nowMs = 3_000L
        )!!

        assertEquals(DurableTaskStatus.PAUSED, paused.status)
        assertEquals(TaskPauseReason.SYSTEM, paused.pauseReason)
        assertEquals(ExternalOperationStatus.UNKNOWN, paused.externalOperations.single().externalStatus)
        assertNull(paused.externalOperations.single().nextPollAt)
        assertEquals("POLL_ATTEMPTS_EXHAUSTED", paused.externalOperations.single().reconciliationRequiredReason)
        assertEquals(TaskRunStatus.PAUSED, paused.runs.single().status)
        assertEquals(TaskStepStatus.PAUSED, paused.plan.single().status)
    }

    @Test
    fun gsonRoundTripPreservesNewFieldsAndLegacyMissingFieldsNormalizeSafely() {
        val waiting = runningTask().waitForExternalOperation(
            ExternalOperation(
                operationId = "op-gson",
                taskId = "task-lifecycle",
                runId = "run-1",
                stepId = "step-1",
                externalProvider = "ci-provider",
                startedAt = 1_500L,
                timeoutPolicy = ExternalOperationTimeoutPolicy(
                    deadlineAt = 99_000L,
                    action = ExternalOperationTimeoutAction.REQUIRE_USER_ACTION
                ),
                retryPolicy = ExternalOperationRetryPolicy(maxAttempts = 9),
                cancellationState = ExternalOperationCancellationState.REQUESTED
            ),
            nowMs = 1_600L
        )!!

        val restored = gson.fromJson(gson.toJson(waiting), DurableTask::class.java)
        assertEquals(waiting, restored)
        assertEquals(ExternalOperationCancellationState.REQUESTED, restored.externalOperations.single().cancellationState)
        assertEquals(ExternalOperationTimeoutAction.REQUIRE_USER_ACTION, restored.externalOperations.single().timeoutPolicy.action)

        val legacyJson = JsonParser.parseString(gson.toJson(DurableTask(
            id = "legacy-task",
            title = "Legacy fixture",
            description = "old json",
            agentId = "agent",
            input = "input"
        ))).asJsonObject.apply {
            remove("externalOperations")
            remove("budgetPauseReason")
        }
        val legacy = gson.fromJson(legacyJson, DurableTask::class.java).withDeserializationDefaults()
        assertEquals(emptyList<ExternalOperation>(), legacy.externalOperations)
        assertNull(legacy.budgetPauseReason)
        assertEquals(DurableTaskStatus.QUEUED, legacy.status)
    }

    @Test
    fun childTaskPreservesParentRunAndSourceMessageIdentity() {
        val child = DurableTask(
            id = "child-task",
            title = "Typed child operation",
            description = "test",
            agentId = "agent_loop",
            input = "",
            sourceMessageId = 42L,
            parentTaskId = "chat-task",
            parentRunId = "chat-run",
        )

        val restored = gson.fromJson(gson.toJson(child), DurableTask::class.java)

        assertEquals(42L, restored.sourceMessageId)
        assertEquals("chat-task", restored.parentTaskId)
        assertEquals("chat-run", restored.parentRunId)
    }
}
