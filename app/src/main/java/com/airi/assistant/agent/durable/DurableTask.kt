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

    /** Stable mission aggregate. Legacy tasks normalize to their own task ID. */
    val missionId: String? = null,

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

    /** Durable approval records for task-owned side effects. */
    val approvals: List<TaskApproval> = emptyList(),

    /** One-shot side effects paused before invocation and eligible for explicit resume. */
    val approvalContinuations: List<ApprovalContinuation> = emptyList(),

    /** Sanitized diagnostics retained for replay and support export. */
    val diagnostics: List<TaskDiagnostic> = emptyList(),

    /** Ordered, sanitised execution events retained for replay. */
    val timeline: List<TaskTimelineEvent> = emptyList(),

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
            taskId = id,
            missionId = missionId ?: id,
            projectId = projectId,
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
                    step.copy(runId = runId, status = TaskStepStatus.RUNNING, startedAtMs = nowMs)
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
            plan = plan.map { step ->
                if (
                    step.id == stepId &&
                    step.status != TaskStepStatus.COMPLETED &&
                    step.status != TaskStepStatus.FAILED
                ) {
                    step.copy(
                        runId = currentRunId ?: step.runId,
                        status = TaskStepStatus.RUNNING,
                        startedAtMs = step.startedAtMs.takeIf { it > 0 } ?: nowMs
                    )
                } else step
            },
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

    /** Adds a validated artifact reference produced by the current execution. */
    fun linkArtifact(artifactId: String, nowMs: Long = System.currentTimeMillis()): DurableTask = copy(
        artifactIds = (artifactIds + artifactId).distinct(),
        updatedAtMs = nowMs
    )

    fun requestApproval(approval: TaskApproval): DurableTask {
        val ownedApproval = approval.copy(
            taskId = id,
            missionId = missionId ?: id,
            projectId = projectId,
            runId = approval.runId ?: currentRunId,
            stepId = approval.stepId ?: currentStepId
        )
        return copy(
            approvalIds = (approvalIds + ownedApproval.id).distinct(),
            approvals = approvals.filterNot { it.id == ownedApproval.id } + ownedApproval,
            updatedAtMs = ownedApproval.requestedAtMs
        )
    }

    fun decideApproval(
        approvalId: String,
        status: TaskApprovalStatus,
        scope: ApprovalGrantScope,
        reason: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        approvals = approvals.map { approval ->
            if (approval.id == approvalId) {
                approval.copy(
                    status = status,
                    grantScope = scope,
                    decisionReason = reason,
                    decidedAtMs = nowMs
                )
            } else {
                approval
            }
        },
        approvalContinuations = approvalContinuations.map { continuation ->
            if (
                continuation.approvalId == approvalId &&
                status != TaskApprovalStatus.APPROVED
            ) {
                continuation.reject(reason.ifBlank { "Approval ${status.name.lowercase()}" }, nowMs)
            } else {
                continuation
            }
        },
        updatedAtMs = nowMs
    )

    /**
     * Stops the currently running exact step before its side effect is invoked.
     * The continuation is normalized to this aggregate and rejected unless every
     * ownership and execution coordinate matches the pending approval.
     */
    fun pauseForApproval(
        approvalId: String,
        continuation: ApprovalContinuation,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        val approval = approvals.firstOrNull { it.id == approvalId }
            ?.takeIf { it.status == TaskApprovalStatus.PENDING }
            ?: return null
        val resolvedRunId = currentRunId ?: return null
        val resolvedStepId = currentStepId ?: return null
        val owned = continuation.copy(
            approvalId = approvalId,
            taskId = id,
            missionId = missionId ?: id,
            projectId = projectId,
            runId = resolvedRunId,
            stepId = resolvedStepId,
            expiresAtMs = minOf(continuation.expiresAtMs, approval.expiresAtMs)
        )
        if (
            approval.runId != resolvedRunId ||
            approval.stepId != resolvedStepId ||
            continuation.runId != resolvedRunId ||
            continuation.stepId != resolvedStepId ||
            continuation.projectId != projectId ||
            !owned.invocation.isSafeToPersist() ||
            owned.isExpired(nowMs)
        ) return null

        return copy(
            status = DurableTaskStatus.PAUSED,
            updatedAtMs = nowMs,
            progressMessage = "Waiting for approval",
            approvalContinuations = approvalContinuations.filterNot { it.id == owned.id } + owned,
            plan = plan.map { step ->
                if (step.id == resolvedStepId && step.runId == resolvedRunId) {
                    step.copy(status = TaskStepStatus.PAUSED)
                } else step
            },
            runs = runs.map { run ->
                if (run.id == resolvedRunId) run.copy(status = TaskRunStatus.PAUSED) else run
            }
        )
    }

    /**
     * Atomically consumes one approved continuation and returns the claimed
     * record. A second call returns null, which is the duplicate-call guard.
     */
    fun claimApprovedContinuation(
        approvalId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<DurableTask, ApprovalContinuation>? {
        val approval = approvals.firstOrNull { it.id == approvalId }
            ?.takeIf { it.status == TaskApprovalStatus.APPROVED }
            ?: return null
        val continuation = approvalContinuations.firstOrNull {
            it.approvalId == approvalId && it.status == ApprovalContinuationStatus.PENDING
        } ?: return null
        if (
            status != DurableTaskStatus.PAUSED ||
            currentRunId != continuation.runId ||
            currentStepId != continuation.stepId ||
            approval.runId != continuation.runId ||
            approval.stepId != continuation.stepId ||
            continuation.taskId != id ||
            continuation.missionId != (missionId ?: id) ||
            continuation.projectId != projectId
        ) return null
        val claimed = continuation.claim(nowMs) ?: return null
        val resumed = copy(
            status = DurableTaskStatus.RUNNING,
            updatedAtMs = nowMs,
            progressMessage = "Resuming approved action",
            approvalContinuations = approvalContinuations.map {
                if (it.id == claimed.id) claimed else it
            },
            plan = plan.map { step ->
                if (step.id == claimed.stepId && step.runId == claimed.runId) {
                    step.copy(status = TaskStepStatus.RUNNING)
                } else step
            },
            runs = runs.map { run ->
                if (run.id == claimed.runId) run.copy(status = TaskRunStatus.RUNNING) else run
            }
        )
        return resumed to claimed
    }

    fun finishContinuation(
        continuationId: String,
        outcome: String,
        succeeded: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        val current = approvalContinuations.firstOrNull { it.id == continuationId }
            ?: return null
        val finished: ApprovalContinuation = (if (succeeded) {
            current.complete(outcome, nowMs)
        } else {
            current.fail(outcome, nowMs)
        }) ?: return null
        return copy(
            updatedAtMs = nowMs,
            approvalContinuations = approvalContinuations.map {
                if (it.id == continuationId) finished else it
            }
        )
    }

    fun addDiagnostic(
        diagnostic: TaskDiagnostic,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        diagnostics = diagnostics + diagnostic,
        updatedAtMs = nowMs
    )

    fun appendTimeline(
        event: TaskTimelineEvent,
        maxEvents: Int = MAX_TIMELINE_EVENTS
    ): DurableTask = copy(
        timeline = (timeline + event).takeLast(maxEvents.coerceAtLeast(1)),
        updatedAtMs = event.recordedAtMs
    )

    private companion object {
        const val MAX_TIMELINE_EVENTS = 400
    }

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
    PAUSED,
    COMPLETED,
    FAILED,
    SKIPPED
}

enum class TaskRunStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TaskPlanStep(
    val id: String,
    val title: String,
    /** Owning run once execution begins; null is valid while the plan is pending. */
    val runId: String? = null,
    val status: TaskStepStatus = TaskStepStatus.PENDING,
    val startedAtMs: Long = -1L,
    val completedAtMs: Long = -1L,
    val toolSummary: String = "",
    val error: String = ""
)

data class TaskRun(
    val id: String,
    val taskId: String = "",
    val missionId: String = "",
    val projectId: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long = -1L,
    val currentStepId: String? = null,
    val status: TaskRunStatus,
    val error: String = ""
)

enum class TaskApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED
}

enum class ApprovalGrantScope {
    ONCE,
    TASK,
    PROJECT
}

data class TaskApproval(
    val id: String,
    val taskId: String = "",
    val missionId: String = "",
    val projectId: String? = null,
    val action: String,
    val description: String,
    val riskLevel: String,
    val requestedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long,
    val status: TaskApprovalStatus = TaskApprovalStatus.PENDING,
    val grantScope: ApprovalGrantScope = ApprovalGrantScope.ONCE,
    val decisionReason: String = "",
    val decidedAtMs: Long = -1L,
    val runId: String? = null,
    val stepId: String? = null
)

enum class TaskTimelineEventType {
    TASK_REGISTERED,
    RUN_STARTED,
    STEP_STARTED,
    STEP_PROGRESS,
    TOOL_REQUESTED,
    ARTIFACT_CREATED,
    APPROVAL_REQUESTED,
    APPROVAL_DECIDED,
    APPROVAL_PAUSED,
    APPROVAL_RESUMED,
    APPROVAL_CONTINUATION_COMPLETED,
    RECOVERY_ATTEMPTED,
    STEP_COMPLETED,
    STEP_FAILED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    CONTINUITY_MERGED
}

sealed class ContinuityMergeResult {
    data class Merged(val remoteWasRunning: Boolean) : ContinuityMergeResult()
    data object UnknownTask : ContinuityMergeResult()
    data object LocalNewer : ContinuityMergeResult()
    data object LocalExecutionActive : ContinuityMergeResult()
    data object UnsupportedSchema : ContinuityMergeResult()
}

data class TaskTimelineEvent(
    val type: TaskTimelineEventType,
    val summary: String,
    val detail: String = "",
    val runId: String? = null,
    val stepId: String? = null,
    val recordedAtMs: Long = System.currentTimeMillis()
)

data class TaskDiagnostic(
    val code: String,
    val message: String,
    val recordedAtMs: Long = System.currentTimeMillis()
)
