package com.airi.assistant.agent.durable

/**
 * Minimal cross-device task state. It deliberately excludes task input, result,
 * checkpoint, artifact paths, approvals, diagnostics, timeline text, and agent
 * context. Those values can contain private content or device-local references.
 *
 * A snapshot is progress metadata, not a remote execution command. A receiving
 * device may display or merge a newer snapshot for a task it already owns, but
 * it must never execute a task merely because a remote snapshot says RUNNING.
 */
data class TaskContinuitySnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val taskId: String,
    val projectId: String?,
    val status: DurableTaskStatus,
    val updatedAtMs: Long,
    val currentRunId: String?,
    val currentStepId: String?,
    val progressPercent: Int,
    val executionNode: String?,
    val plan: List<StepState>
) {
    data class StepState(
        val id: String,
        val status: TaskStepStatus,
        val startedAtMs: Long,
        val completedAtMs: Long
    )

    companion object {
        const val SCHEMA_VERSION = 1

        fun from(task: DurableTask) = TaskContinuitySnapshot(
            taskId = task.id,
            projectId = task.projectId,
            status = task.status,
            updatedAtMs = task.updatedAtMs,
            currentRunId = task.currentRunId,
            currentStepId = task.currentStepId,
            progressPercent = task.progressPercent,
            executionNode = task.executionNode,
            plan = task.plan.map { step ->
                StepState(
                    id = step.id,
                    status = step.status,
                    startedAtMs = step.startedAtMs,
                    completedAtMs = step.completedAtMs
                )
            }
        )
    }
}
