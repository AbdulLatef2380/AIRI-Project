package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalOperationPollBackoffTest {
    private fun operation(attempt: Int) = ExternalOperation(
        operationId = "op",
        taskId = "task",
        runId = "run",
        stepId = "step",
        externalProvider = "provider",
        externalReferenceId = "remote-id",
        startedAt = 1L,
        pollAttemptCount = attempt,
        retryPolicy = ExternalOperationRetryPolicy(
            maxAttempts = 10,
            initialBackoffMs = 10_000L,
            maxBackoffMs = 50_000L
        )
    )

    @Test
    fun growsExponentialWindowAndCapsItBeforeApplyingFullJitter() {
        assertEquals(5_000L, ExternalOperationPollBackoff.nextDelayMs(operation(1), jitterUnit = 0.5))
        assertEquals(10_000L, ExternalOperationPollBackoff.nextDelayMs(operation(2), jitterUnit = 0.5))
        assertEquals(25_000L, ExternalOperationPollBackoff.nextDelayMs(operation(8), jitterUnit = 0.5))
    }

    @Test
    fun retryAfterIsAConsistentLowerBoundAndDelayNeverCollapsesToZero() {
        assertEquals(
            70_000L,
            ExternalOperationPollBackoff.nextDelayMs(operation(1), retryAfterMs = 70_000L, jitterUnit = 0.0)
        )
        assertEquals(1_000L, ExternalOperationPollBackoff.nextDelayMs(operation(1), jitterUnit = 0.0))
    }

    @Test
    fun scheduledTimeIsClampedToDeadlineAndSaturatesOnOverflow() {
        assertEquals(5_000L, ExternalOperationPollBackoff.nextPollAtMs(4_000L, 10_000L, 5_000L))
        assertEquals(Long.MAX_VALUE, ExternalOperationPollBackoff.nextPollAtMs(Long.MAX_VALUE - 10L, 50L, Long.MAX_VALUE))
    }
}
