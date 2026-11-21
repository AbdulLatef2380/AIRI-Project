package com.airi.assistant.agent.durable

/**
 * The generic worker may claim only one not-yet-completed plan step.
 *
 * This boundary intentionally does not replay an entire task from an opaque
 * prompt when the current step or a side-effect operation is ambiguous.
 */
object DurableTaskExecutionBoundary {
    fun claim(task: DurableTask, runId: String): DurableTaskStepLease {
        require(runId.isNotBlank()) { "Execution run ID must be non-blank" }
        require(task.status == DurableTaskStatus.QUEUED && !task.isTerminal) {
            "Only a queued non-terminal task can enter the generic step worker"
        }
        require(task.externalOperations.none { it.externalStatus == ExternalOperationStatus.UNKNOWN }) {
            "Unknown external operation outcome requires reconciliation before execution"
        }
        require(task.operations.none {
            it.status == DurableOperationStatus.CLAIMED ||
                it.status == DurableOperationStatus.UNKNOWN
        }) {
            "An ambiguous side-effect operation requires reconciliation before execution"
        }

        val step = task.currentStepId?.let { id -> task.plan.firstOrNull { it.id == id } }
            ?: task.plan.firstOrNull {
                it.status == TaskStepStatus.PENDING || it.status == TaskStepStatus.PAUSED
            }
        require(step != null) { "Queued task has no executable plan step" }
        require(step.status == TaskStepStatus.PENDING || step.status == TaskStepStatus.PAUSED) {
            "Generic worker cannot replay step ${step.id} in status ${step.status}"
        }

        return DurableTaskStepLease(
            taskId = task.id,
            runId = runId,
            stepId = step.id,
            checkpointData = task.checkpointData,
        )
    }
}

data class DurableTaskStepLease(
    val taskId: String,
    val runId: String,
    val stepId: String,
    val checkpointData: String,
)
