package com.airi.assistant.agent.scheduler

/**
 * Decides the terminal worker result after a scheduled task fails.
 * A maintenance task is infrastructure-only: it must never fall through to an
 * agent execution when maintenance handling has failed.
 */
internal object ScheduledWorkerOutcomePolicy {
    const val MAX_RETRY_ATTEMPTS = 3

    fun failureOutcome(isTransient: Boolean, runAttemptCount: Int): ScheduledJobOutcome =
        if (isTransient && runAttemptCount < MAX_RETRY_ATTEMPTS) {
            ScheduledJobOutcome.RETRYING
        } else {
            ScheduledJobOutcome.FAILED
        }

    fun keepsManualRunActive(outcome: ScheduledJobOutcome): Boolean =
        outcome == ScheduledJobOutcome.RETRYING
}
