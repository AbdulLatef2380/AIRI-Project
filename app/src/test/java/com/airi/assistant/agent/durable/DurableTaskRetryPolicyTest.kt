package com.airi.assistant.agent.durable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskRetryPolicyTest {
    private fun task(
        operations: List<DurableOperation> = emptyList(),
        externalOperations: List<ExternalOperation> = emptyList(),
    ) = DurableTask(
        id = "task-1",
        title = "retry",
        description = "retry policy",
        agentId = "agent",
        input = "input",
        operations = operations,
        externalOperations = externalOperations,
    )

    @Test
    fun preparedOrTerminalOperationMayRetry() {
        val operation = DurableOperation(
            operationId = "op-1",
            taskId = "task-1",
            runId = "run-1",
            stepId = "step-1",
            actionId = "action-1",
            idempotencyKey = "key-1",
            payloadSha256 = "hash",
            provider = "fake",
            status = DurableOperationStatus.PREPARED,
        )
        assertTrue(DurableTaskRetryPolicy.mayReplayAgent(task(listOf(operation))))
    }

    @Test
    fun claimedOperationBlocksOpaqueReplay() {
        val operation = DurableOperation(
            operationId = "op-1",
            taskId = "task-1",
            runId = "run-1",
            stepId = "step-1",
            actionId = "action-1",
            idempotencyKey = "key-1",
            payloadSha256 = "hash",
            provider = "fake",
            status = DurableOperationStatus.CLAIMED,
        )
        assertFalse(DurableTaskRetryPolicy.mayReplayAgent(task(listOf(operation))))
    }

    @Test
    fun unknownExternalOperationBlocksOpaqueReplay() {
        val operation = ExternalOperation(
            operationId = "external-1",
            taskId = "task-1",
            runId = "run-1",
            stepId = "step-1",
            externalProvider = "fake-ci",
            externalStatus = ExternalOperationStatus.UNKNOWN,
            startedAt = 1L,
        )
        assertFalse(DurableTaskRetryPolicy.mayReplayAgent(task(externalOperations = listOf(operation))))
    }
}
