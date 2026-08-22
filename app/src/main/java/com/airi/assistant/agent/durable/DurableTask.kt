package com.airi.assistant.agent.durable

import java.util.UUID

/**
 * Persistent product task. A task is the durable owner of its execution runs,
 * plan steps, artifacts, approvals, diagnostics, and scoped context.
 *
 * The default values are intentionally backwards-compatible with task records
 * written before project ownership and run metadata were introduced.
 */
data class DurableTask(
    /** Stable UUID. Used as the WorkManager unique-work name. */
    val id: String = UUID.randomUUID().toString(),

    /** Project/workspace that owns the task. Null denotes an unscoped legacy task. */
    val projectId: String? = null,

    /** Firebase UID or local identity that owns the task. */
    val ownerId: String = "anonymous",

    /** Human-readable title shown in notifications and task history. */
    val title: String,

    /** Detailed description of the requested outcome. */
    val description: String,

    /** ID of the sub-agent that executes this task, or "auto" for routed work. */
    val agentId: String,

    /** Full user input or task specification passed to the agent. */
    val input: String,

    /** Epoch ms when the task was created/queued. */
    val queuedAtMs: Long = System.currentTimeMillis(),

    /** Epoch ms when the task first started running (-1 = not started). */
    val startedAtMs: Long = -1L,

    /** Epoch ms when the task reached a terminal state (-1 = ongoing). */
    val finishedAtMs: Long = -1L,

    /** Epoch ms of the most recent mutation. */
    val updatedAtMs: Long = queuedAtMs,

    /** Whether the device must be connected to a network to execute. */
    val requiresNetwork: Boolean = false,

    /** Whether the device should be charging for execution. */
    val requiresCharging: Boolean = false,

    /** Current lifecycle state. */
    val status: DurableTaskStatus = DurableTaskStatus.QUEUED,

    /** Number of retry attempts so far. */
    val attemptCount: Int = 0,

    /** Maximum retry attempts before a terminal failure. */
    val maxAttempts: Int = 3,

    /** 0–100 progress estimate. -1 = indeterminate. */
    val progressPercent: Int = -1,

    /** Human-readable status message. */
    val progressMessage: String = "",

    /** Final result text, set when the task completes. */
    val result: String = "",

    /** Error reason, set when the task fails. */
    val errorReason: String = "",

    /** Opaque checkpoint written by the executing agent after recoverable work. */
    val checkpointData: String = "",

    /** Whether to post a system notification for task progress or completion. */
    val showNotification: Boolean = true,

    /** Current execution run, if any. */
    val currentRunId: String? = null,

    /** Current or most recently executed plan step, if known. */
    val currentStepId: String? = null,

    /** Declarative plan associated with this task. */
    val plan: List<TaskPlanStep> = emptyList(),

    /** Produced artifact identifiers owned by this task. */
    val artifactIds: List<String> = emptyList(),

    /** Approval request identifiers associated with this task. */
    val approvalIds: List<String> = emptyList(),

    /** Sanitized diagnostics retained for replay and support export. */
    val diagnostics: List<TaskDiagnostic> = emptyList(),

    /** Memory boundary applied to the task. */
    val memoryScope: TaskScope = TaskScope.SESSION,

    /** Knowledge boundary applied to the task. */
    val knowledgeScope: TaskScope = TaskScope.PROJECT,

    /** Device/node selected to execute the current run. */
    val executionNode: String? = null,

    /** Append-only history of execution runs. */
    val runs: List<TaskRun> = emptyList()
) {
    val isTerminal: Boolean
        get() = status == DurableTaskStatus.COMPLETED ||
            status == DurableTaskStatus.FAILED ||
            status == DurableTaskStatus.CANCELLED

    val canRetry: Boolean
        get() = status == DurableTaskStatus.FAILED && attemptCount < maxAttempts

    fun beginRun(
        runId: String,
        stepId: String? = plan.firstOrNull()?.id,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask {
        val run = TaskRun(
            id = runId,
            startedAtMs = nowMs,
            currentStepId = stepId,
            status = TaskRunStatus.RUNNING
        )
        return copy(
            status = DurableTaskStatus.RUNNING,
            startedAtMs = startedAtMs.takeIf { it > 0 } ?: nowMs,
            updatedAtMs = nowMs,
            currentRunId = runId,
            currentStepId = stepId,
            plan = plan.map { step ->
                if (step.id == stepId && step.status == TaskStepStatus.PENDING) {
                    step.copy(status = TaskStepStatus.RUNNING, startedAtMs = nowMs)
                } else {
                    step
                }
            },
            runs = runs.filterNot { it.id == runId } + run
        )
    }

    fun updateStep(
        stepId: String?,
        progressPercent: Int = this.progressPercent,
        progressMessage: String = this.progressMessage,
        checkpointData: String = this.checkpointData,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask {
        val updatedRun = currentRunId?.let { runId ->
            runs.map { run ->
                if (run.id == runId) run.copy(currentStepId = stepId ?: run.currentStepId) else run
            }
        } ?: runs
        return copy(
            updatedAtMs = nowMs,
            currentStepId = stepId ?: currentStepId,
            progressPercent = progressPercent,
            progressMessage = progressMessage,
            checkpointData = checkpointData,
            runs = updatedRun
        )
    }

    fun completeStep(
        stepId: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        currentStepId = stepId,
        updatedAtMs = nowMs,
        plan = plan.map { step ->
            if (step.id == stepId && step.status != TaskStepStatus.COMPLETED) {
                step.copy(
                    status = TaskStepStatus.COMPLETED,
                    startedAtMs = step.startedAtMs.takeIf { it > 0 } ?: nowMs,
                    completedAtMs = nowMs
                )
            } else {
                step
            }
        }
    )

    fun failStep(
        stepId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        currentStepId = stepId,
        updatedAtMs = nowMs,
        plan = plan.map { step ->
            if (step.id == stepId) {
                step.copy(
                    status = TaskStepStatus.FAILED,
                    startedAtMs = step.startedAtMs.takeIf { it > 0 } ?: nowMs,
                    completedAtMs = nowMs,
                    error = reason
                )
            } else {
                step
            }
        }
    )

    fun complete(
        result: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.COMPLETED,
        result = result,
        errorReason = "",
        finishedAtMs = nowMs,
        updatedAtMs = nowMs,
        progressPercent = 100,
        plan = plan.map { step ->
            if (step.id == currentStepId && step.status == TaskStepStatus.RUNNING) {
                step.copy(status = TaskStepStatus.COMPLETED, completedAtMs = nowMs)
            } else {
                step
            }
        },
        runs = finishCurrentRun(TaskRunStatus.COMPLETED, nowMs)
    )

    fun fail(
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.FAILED,
        errorReason = reason,
        finishedAtMs = nowMs,
        updatedAtMs = nowMs,
        plan = plan.map { step ->
            if (step.id == currentStepId && step.status == TaskStepStatus.RUNNING) {
                step.copy(status = TaskStepStatus.FAILED, completedAtMs = nowMs, error = reason)
            } else {
                step
            }
        },
        runs = finishCurrentRun(TaskRunStatus.FAILED, nowMs, reason)
    )

    fun cancel(
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.CANCELLED,
        finishedAtMs = nowMs,
        updatedAtMs = nowMs,
        runs = finishCurrentRun(TaskRunStatus.CANCELLED, nowMs)
    )

    fun addDiagnostic(
        diagnostic: TaskDiagnostic,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        diagnostics = diagnostics + diagnostic,
        updatedAtMs = nowMs
    )

    private fun finishCurrentRun(
        status: TaskRunStatus,
        nowMs: Long,
        error: String = ""
    ): List<TaskRun> = runs.map { run ->
        if (run.id == currentRunId) {
            run.copy(status = status, finishedAtMs = nowMs, error = error)
        } else {
            run
        }
    }
}

enum class DurableTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TaskScope {
    SESSION,
    PROJECT,
    USER,
    DEVICE
}

enum class TaskStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

enum class TaskRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TaskPlanStep(
    val id: String,
    val title: String,
    val status: TaskStepStatus = TaskStepStatus.PENDING,
    val startedAtMs: Long = -1L,
    val completedAtMs: Long = -1L,
    val toolSummary: String = "",
    val error: String = ""
)

data class TaskRun(
    val id: String,
    val startedAtMs: Long,
    val finishedAtMs: Long = -1L,
    val currentStepId: String? = null,
    val status: TaskRunStatus,
    val error: String = ""
)

data class TaskDiagnostic(
    val code: String,
    val message: String,
    val recordedAtMs: Long = System.currentTimeMillis()
)
