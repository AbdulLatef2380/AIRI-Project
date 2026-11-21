package com.airi.assistant.agent.durable

/**
 * Decides whether a failed durable run is safe to hand back to WorkManager.
 *
 * This is deliberately pure: an UNKNOWN/CLAIMED operation or external job is
 * evidence that the provider may have accepted an effect, so replay must wait
 * for reconciliation instead of invoking the opaque agent prompt again.
 */
object DurableTaskRetryPolicy {
    fun mayReplayAgent(task: DurableTask): Boolean =
        task.operations.none {
            it.status == DurableOperationStatus.CLAIMED ||
                it.status == DurableOperationStatus.UNKNOWN
        } && task.externalOperations.none {
            it.externalStatus == ExternalOperationStatus.UNKNOWN
        }
}
