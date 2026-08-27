package com.airi.assistant.agent.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledWorkerOutcomePolicyTest {

    @Test
    fun transientFailureRetriesOnlyWithinTheBoundedBudget() {
        assertEquals(
            ScheduledJobOutcome.RETRYING,
            ScheduledWorkerOutcomePolicy.failureOutcome(isTransient = true, runAttemptCount = 0),
        )
        assertEquals(
            ScheduledJobOutcome.RETRYING,
            ScheduledWorkerOutcomePolicy.failureOutcome(
                isTransient = true,
                runAttemptCount = ScheduledWorkerOutcomePolicy.MAX_RETRY_ATTEMPTS - 1,
            ),
        )
        assertEquals(
            ScheduledJobOutcome.FAILED,
            ScheduledWorkerOutcomePolicy.failureOutcome(
                isTransient = true,
                runAttemptCount = ScheduledWorkerOutcomePolicy.MAX_RETRY_ATTEMPTS,
            ),
        )
    }

    @Test
    fun permanentFailureNeverRetriesAndUnlocksManualRun() {
        val outcome = ScheduledWorkerOutcomePolicy.failureOutcome(
            isTransient = false,
            runAttemptCount = 0,
        )

        assertEquals(ScheduledJobOutcome.FAILED, outcome)
        assertFalse(ScheduledWorkerOutcomePolicy.keepsManualRunActive(outcome))
        assertTrue(
            ScheduledWorkerOutcomePolicy.keepsManualRunActive(ScheduledJobOutcome.RETRYING)
        )
    }
}
