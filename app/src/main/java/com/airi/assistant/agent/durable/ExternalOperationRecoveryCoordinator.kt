package com.airi.assistant.agent.durable

/**
 * Pure policy for reconciling persisted external-operation waits after process
 * restart. It never submits or repeats the original side effect; it only marks
 * an idempotent status lookup as schedulable when the provider reference and
 * durable due time are present.
 */
object ExternalOperationRecoveryCoordinator {
    fun plan(tasks: List<DurableTask>, nowMs: Long): ExternalOperationRecoveryPlan {
        val actions = mutableListOf<ExternalOperationRecoveryAction>()

        tasks.asSequence()
            .filter {
                it.status == DurableTaskStatus.WAITING_EXTERNAL ||
                    it.status == DurableTaskStatus.WAITING_TIMER
            }
            .forEach { task ->
                task.externalOperations
                    .filterNot { it.externalStatus.isTerminal }
                    .forEach { operation ->
                        val issue = validate(task, operation, nowMs)
                        if (issue != null) {
                            actions += ExternalOperationRecoveryAction.NeedsReconciliation(
                                taskId = task.id,
                                operationId = operation.operationId,
                                reason = issue
                            )
                        } else {
                            val pollAt = requireNotNull(operation.nextPollAt)
                            actions += ExternalOperationRecoveryAction.ScheduleStatusCheck(
                                taskId = task.id,
                                operationId = operation.operationId,
                                runAtMs = pollAt,
                                delayMs = (pollAt - nowMs).coerceAtLeast(0L)
                            )
                        }
                    }
            }

        return ExternalOperationRecoveryPlan(actions)
    }

    private fun validate(
        task: DurableTask,
        operation: ExternalOperation,
        nowMs: Long
    ): ExternalOperationRecoveryIssue? {
        if (operation.operationId.isBlank() || operation.taskId != task.id) {
            return ExternalOperationRecoveryIssue.OWNERSHIP_MISMATCH
        }
        if (operation.externalProvider.isBlank() || operation.externalReferenceId.isNullOrBlank()) {
            return ExternalOperationRecoveryIssue.MISSING_PROVIDER_REFERENCE
        }
        if (
            operation.runId.isBlank() || operation.stepId.isBlank() ||
            task.runs.none { it.id == operation.runId && it.status == TaskRunStatus.WAITING_EXTERNAL } ||
            task.plan.none { it.id == operation.stepId && it.runId == operation.runId }
        ) {
            return ExternalOperationRecoveryIssue.OWNERSHIP_MISMATCH
        }
        if (nowMs >= operation.timeoutPolicy.deadlineAt) {
            return ExternalOperationRecoveryIssue.OPERATION_DEADLINE_REACHED
        }
        if (operation.retryPolicy.maxAttempts <= 0 || operation.pollAttemptCount >= operation.retryPolicy.maxAttempts) {
            return ExternalOperationRecoveryIssue.POLL_ATTEMPTS_EXHAUSTED
        }
        if (operation.nextPollAt == null) {
            return ExternalOperationRecoveryIssue.MISSING_DURABLE_POLL_TIME
        }
        return null
    }
}

data class ExternalOperationRecoveryPlan(
    val actions: List<ExternalOperationRecoveryAction>
)

sealed class ExternalOperationRecoveryAction {
    data class ScheduleStatusCheck(
        val taskId: String,
        val operationId: String,
        val runAtMs: Long,
        val delayMs: Long
    ) : ExternalOperationRecoveryAction()

    data class NeedsReconciliation(
        val taskId: String,
        val operationId: String,
        val reason: ExternalOperationRecoveryIssue
    ) : ExternalOperationRecoveryAction()
}

enum class ExternalOperationRecoveryIssue {
    OWNERSHIP_MISMATCH,
    MISSING_PROVIDER_REFERENCE,
    MISSING_DURABLE_POLL_TIME,
    POLL_ATTEMPTS_EXHAUSTED,
    OPERATION_DEADLINE_REACHED
}
