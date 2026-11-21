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

    /** User message that explicitly created this execution task. */
    val sourceMessageId: Long? = null,

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

    /** Typed reason for a resumable pause; null for non-paused and legacy records. */
    val pauseReason: TaskPauseReason? = null,

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
    val runs: List<TaskRun> = emptyList(),

    /** Durable external jobs (CI/build/etc.) that outlive an execution run. */
    val externalOperations: List<ExternalOperation> = emptyList(),

    /** Side-effect intents persisted before a connector is invoked. */
    val operations: List<DurableOperation> = emptyList(),

    /** Typed reason when a budget pause is awaiting extension or replenishment. */
    val budgetPauseReason: BudgetPauseReason? = null,

    /** Monotonic aggregate version used to reject stale task commands. */
    val stateVersion: Long = 0L,

    /** Version of the execution checkpoint format, independent from the task schema. */
    val checkpointSchemaVersion: Int = CURRENT_CHECKPOINT_SCHEMA_VERSION,

    /** Optional user instruction attached to the next explicit continuation. */
    val continuationInstruction: String = ""
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
        require(status == DurableTaskStatus.QUEUED) {
            "A new run may start only from QUEUED, not $status"
        }
        require(runId.isNotBlank() && runs.none { it.id == runId }) {
            "Run ID must be non-blank and unique within the task"
        }
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
            pauseReason = null,
            attemptCount = (attemptCount + 1).coerceAtMost(maxAttempts),
            startedAtMs = startedAtMs.takeIf { it > 0 } ?: nowMs,
            updatedAtMs = nowMs,
            currentRunId = runId,
            budgetPauseReason = null,
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

    /**
     * Start a new run only after an explicit continuation command.
     * Unknown external operations block continuation until reconciliation.
     */
    fun resume(
        runId: String,
        userInstruction: String = "",
        stepId: String? = currentStepId ?: plan.firstOrNull { it.status == TaskStepStatus.PENDING }?.id,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask {
        require(status in RESUMABLE_STATUSES) { "Task is not resumable from $status" }
        require(runId.isNotBlank() && runs.none { it.id == runId }) {
            "Continuation run ID must be non-blank and unique within the task"
        }
        require(externalOperations.none { it.externalStatus == ExternalOperationStatus.UNKNOWN }) {
            "Cannot resume while an external operation has an unknown outcome"
        }
        require(checkpointSchemaVersion == CURRENT_CHECKPOINT_SCHEMA_VERSION) {
            "Unsupported checkpoint schema version $checkpointSchemaVersion"
        }
        val resumed = copy(
            status = DurableTaskStatus.RUNNING,
            pauseReason = null,
            budgetPauseReason = null,
            finishedAtMs = -1L,
            updatedAtMs = nowMs,
            currentRunId = runId,
            currentStepId = stepId,
            continuationInstruction = userInstruction.take(MAX_CONTINUATION_INSTRUCTION_CHARS),
            runs = runs + TaskRun(
                id = runId,
                taskId = id,
                missionId = missionId ?: id,
                projectId = projectId,
                startedAtMs = nowMs,
                currentStepId = stepId,
                status = TaskRunStatus.RUNNING
            ),
            plan = plan.map { step ->
                if (step.id == stepId && step.status in setOf(TaskStepStatus.PENDING, TaskStepStatus.PAUSED)) {
                    step.copy(runId = runId, status = TaskStepStatus.RUNNING, startedAtMs = step.startedAtMs.takeIf { it > 0 } ?: nowMs)
                } else step
            }
        )
        return resumed.appendTimeline(
            TaskTimelineEvent(
                type = TaskTimelineEventType.RUN_STARTED,
                summary = "Explicit continuation run started",
                detail = if (userInstruction.isBlank()) "checkpoint-resume" else "user-guided-resume",
                runId = runId,
                stepId = stepId,
                recordedAtMs = nowMs
            )
        )
    }

    /** Persist an idempotent side-effect intent before calling an external connector. */
    fun prepareOperation(operation: DurableOperation, nowMs: Long = System.currentTimeMillis()): DurableTask? {
        if (
            operation.taskId != id || operation.operationId.isBlank() ||
            operation.actionId.isBlank() || operation.idempotencyKey.isBlank() ||
            operation.payloadSha256.isBlank() ||
            operations.any { it.operationId == operation.operationId || it.idempotencyKey == operation.idempotencyKey }
        ) return null
        return copy(
            operations = operations + operation.copy(
                status = DurableOperationStatus.PREPARED,
                preparedAtMs = nowMs,
                updatedAtMs = nowMs
            ),
            updatedAtMs = nowMs
        )
    }

    /** Claim a prepared intent exactly once; stale or terminal claims are rejected. */
    fun claimOperation(operationId: String, nowMs: Long = System.currentTimeMillis()): DurableTask? {
        val current = operations.firstOrNull { it.operationId == operationId } ?: return null
        if (current.status != DurableOperationStatus.PREPARED) return null
        return copy(
            operations = operations.map {
                if (it.operationId == operationId) it.copy(status = DurableOperationStatus.CLAIMED, updatedAtMs = nowMs) else it
            },
            updatedAtMs = nowMs
        )
    }

    /** Record a definitive connector result. An UNKNOWN result is never auto-replayed. */
    fun completeOperation(
        operationId: String,
        status: DurableOperationStatus,
        providerReference: String? = null,
        safeError: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        require(status in setOf(DurableOperationStatus.SUCCEEDED, DurableOperationStatus.FAILED, DurableOperationStatus.UNKNOWN)) {
            "Operation completion must be terminal"
        }
        val current = operations.firstOrNull { it.operationId == operationId } ?: return null
        if (current.status != DurableOperationStatus.CLAIMED) return null
        return copy(
            operations = operations.map {
                if (it.operationId == operationId) it.copy(
                    status = status,
                    providerReference = providerReference ?: it.providerReference,
                    safeError = safeError.take(200),
                    completedAtMs = nowMs,
                    updatedAtMs = nowMs
                ) else it
            },
            updatedAtMs = nowMs
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
        pauseReason = null,
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
        pauseReason = null,
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

    /** Return the task to the queue without claiming a terminal outcome. */
    fun retry(
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.QUEUED,
        pauseReason = null,
        finishedAtMs = -1L,
        updatedAtMs = nowMs,
        progressMessage = "Waiting for retry",
        runs = runs.map { run ->
            if (run.id == currentRunId && run.status == TaskRunStatus.RUNNING) {
                run.copy(status = TaskRunStatus.FAILED, finishedAtMs = nowMs, error = "Retry scheduled")
            } else run
        }
    )

    /**
     * Preserve the checkpoint and pause when the platform stops a worker.
     * This does not assert that replay/resume is safe; an explicit user cancellation must use [cancel].
     */
    fun pauseAfterWorkerStop(
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.PAUSED,
        pauseReason = TaskPauseReason.WORKER_INTERRUPTED,
        finishedAtMs = -1L,
        updatedAtMs = nowMs,
        progressMessage = "Worker stopped; task paused to avoid unsafe replay",
        plan = plan.map { step ->
            if (step.id == currentStepId && step.status == TaskStepStatus.RUNNING) {
                step.copy(status = TaskStepStatus.PAUSED)
            } else step
        },
        runs = runs.map { run ->
            if (run.id == currentRunId && run.status == TaskRunStatus.RUNNING) {
                run.copy(status = TaskRunStatus.PAUSED, finishedAtMs = nowMs, error = reason)
            } else run
        }
    )

    /** Pause non-terminally when an extendable execution budget is exhausted. */
    fun pauseForBudget(
        reason: BudgetPauseReason,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        if (status != DurableTaskStatus.RUNNING) return null
        val paused = copy(
            status = DurableTaskStatus.PAUSED_BUDGET,
            pauseReason = null,
            budgetPauseReason = reason,
            finishedAtMs = -1L,
            updatedAtMs = nowMs,
            progressMessage = "Paused: ${reason.name.lowercase().replace('_', ' ')}",
            plan = plan.map { step ->
                if (step.id == currentStepId && step.status == TaskStepStatus.RUNNING) {
                    step.copy(status = TaskStepStatus.PAUSED)
                } else step
            },
            runs = runs.map { run ->
                if (run.id == currentRunId && run.status == TaskRunStatus.RUNNING) {
                    run.copy(status = TaskRunStatus.PAUSED, finishedAtMs = nowMs, error = reason.name)
                } else run
            }
        )
        return paused.appendTimeline(
            TaskTimelineEvent(
                type = TaskTimelineEventType.TASK_BUDGET_PAUSED,
                summary = "Task paused at an execution budget boundary",
                detail = reason.name,
                runId = currentRunId,
                stepId = currentStepId,
                recordedAtMs = nowMs
            )
        )
    }

    /** Yield the current run after an external job is accepted; never replay this run. */
    fun waitForExternalOperation(
        operation: ExternalOperation,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        if (
            status != DurableTaskStatus.RUNNING || currentRunId == null ||
            operation.taskId != id || operation.runId != currentRunId ||
            operation.stepId != currentStepId ||
            plan.none { it.id == operation.stepId && it.runId == currentRunId } ||
            operation.externalProvider.isBlank() || operation.operationId.isBlank() ||
            externalOperations.any { it.operationId == operation.operationId }
        ) return null

        val yielded = copy(
            status = DurableTaskStatus.WAITING_EXTERNAL,
            pauseReason = null,
            budgetPauseReason = null,
            finishedAtMs = -1L,
            updatedAtMs = nowMs,
            progressMessage = "Waiting for ${operation.externalProvider} operation",
            externalOperations = externalOperations + operation,
            plan = plan.map { step ->
                if (step.id == operation.stepId && step.runId == operation.runId) {
                    step.copy(status = TaskStepStatus.WAITING_EXTERNAL)
                } else step
            },
            runs = runs.map { run ->
                if (run.id == currentRunId && run.status == TaskRunStatus.RUNNING) {
                    run.copy(status = TaskRunStatus.WAITING_EXTERNAL, finishedAtMs = nowMs)
                } else run
            }
        )
        return yielded.appendTimeline(
            TaskTimelineEvent(
                type = TaskTimelineEventType.EXTERNAL_OPERATION_STARTED,
                summary = "External operation accepted; current run yielded",
                detail = operation.externalProvider,
                runId = operation.runId,
                stepId = operation.stepId,
                recordedAtMs = nowMs
            )
        )
    }

    /** Pause an external wait when it can no longer be polled safely or within policy. */
    fun pauseForExternalOperationReconciliation(
        operationId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        if (status !in setOf(DurableTaskStatus.WAITING_EXTERNAL, DurableTaskStatus.WAITING_TIMER)) return null
        val current = externalOperations.firstOrNull { it.operationId == operationId } ?: return null
        if (current.externalStatus.isTerminal) return null

        val safeReason = reason.take(120).ifBlank { "reconciliation-required" }
        val paused = copy(
            status = DurableTaskStatus.PAUSED,
            pauseReason = TaskPauseReason.SYSTEM,
            updatedAtMs = nowMs,
            finishedAtMs = -1L,
            progressMessage = "External operation requires reconciliation: $safeReason",
            plan = plan.map { step ->
                if (step.id == current.stepId && step.runId == current.runId &&
                    step.status in setOf(TaskStepStatus.WAITING_EXTERNAL, TaskStepStatus.WAITING_TIMER)
                ) step.copy(status = TaskStepStatus.PAUSED) else step
            },
            runs = runs.map { run ->
                if (run.id == current.runId && run.status == TaskRunStatus.WAITING_EXTERNAL) {
                    run.copy(status = TaskRunStatus.PAUSED, finishedAtMs = nowMs, error = safeReason)
                } else run
            },
            externalOperations = externalOperations.map { operation ->
                if (operation.operationId == operationId) operation.copy(
                    externalStatus = ExternalOperationStatus.UNKNOWN,
                    nextPollAt = null,
                    reconciliationRequiredReason = safeReason
                ) else operation
            }
        )
        return paused
            .appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.EXTERNAL_OPERATION_CHECKED,
                    summary = "External operation requires safe reconciliation",
                    detail = safeReason,
                    runId = current.runId,
                    stepId = current.stepId,
                    recordedAtMs = nowMs
                )
            )
            .appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.TASK_PAUSED,
                    summary = "Task paused until the external result is reconciled",
                    detail = safeReason,
                    runId = current.runId,
                    stepId = current.stepId,
                    recordedAtMs = nowMs
                )
            )
    }

    /**
     * Apply a poll/webhook observation. Pending outcomes persist a next poll time;
     * terminal outcomes await an explicit safe continuation; they never queue
     * the generic worker that would replay the original task input.
     */
    fun recordExternalOperationCheck(
        operationId: String,
        externalStatus: ExternalOperationStatus,
        nextPollAt: Long? = null,
        resultReference: String? = null,
        expectedNextPollAt: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask? {
        if (status !in setOf(DurableTaskStatus.WAITING_EXTERNAL, DurableTaskStatus.WAITING_TIMER)) return null
        val current = externalOperations.firstOrNull { it.operationId == operationId } ?: return null
        if (current.externalStatus.isTerminal) return null
        if (current.nextPollAt != expectedNextPollAt) return null
        if (!externalStatus.isTerminal && nextPollAt == null) return null

        val updatedOperation = current.copy(
            externalStatus = externalStatus,
            nextPollAt = if (externalStatus.isTerminal) null else nextPollAt,
            lastCheckedAt = nowMs,
            resultReference = resultReference ?: current.resultReference,
            pollAttemptCount = current.pollAttemptCount + 1,
            reconciliationRequiredReason = null
        )
        val terminal = externalStatus.isTerminal
        val updated = copy(
            status = if (terminal) DurableTaskStatus.READY_FOR_CONTINUATION else DurableTaskStatus.WAITING_TIMER,
            updatedAtMs = nowMs,
            finishedAtMs = -1L,
            progressMessage = if (terminal) "External operation finished; awaiting a safe continuation"
                else "Waiting to check ${current.externalProvider} again",
            plan = plan.map { step ->
                if (step.id == current.stepId && step.runId == current.runId) {
                    when (externalStatus) {
                        ExternalOperationStatus.SUCCEEDED -> step.copy(
                            status = TaskStepStatus.COMPLETED,
                            completedAtMs = nowMs,
                            error = ""
                        )
                        ExternalOperationStatus.FAILED, ExternalOperationStatus.CANCELLED -> step.copy(
                            status = TaskStepStatus.FAILED,
                            completedAtMs = nowMs,
                            error = "External operation ${externalStatus.name.lowercase()}"
                        )
                        else -> step.copy(status = TaskStepStatus.WAITING_TIMER)
                    }
                } else step
            },
            externalOperations = externalOperations.map {
                if (it.operationId == operationId) updatedOperation else it
            }
        )
        return updated.appendTimeline(
            TaskTimelineEvent(
                type = if (terminal) TaskTimelineEventType.EXTERNAL_OPERATION_COMPLETED
                    else TaskTimelineEventType.EXTERNAL_OPERATION_CHECKED,
                summary = if (terminal) "External operation completed; task awaits a safe continuation"
                    else "External operation remains pending",
                detail = externalStatus.name,
                runId = current.runId,
                stepId = current.stepId,
                recordedAtMs = nowMs
            )
        )
    }

    fun cancel(
        nowMs: Long = System.currentTimeMillis()
    ): DurableTask = copy(
        status = DurableTaskStatus.CANCELLED,
        pauseReason = null,
        budgetPauseReason = null,
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
            !owned.isSafeToPersist() ||
            owned.isExpired(nowMs)
        ) return null

        return copy(
            status = DurableTaskStatus.PAUSED,
            pauseReason = TaskPauseReason.APPROVAL,
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
            pauseReason = null,
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

    /** Gson bypasses Kotlin constructor defaults; normalize fields added after legacy JSON was written. */
    fun withDeserializationDefaults(): DurableTask = copy(
        externalOperations = externalOperations.orEmpty(),
        checkpointSchemaVersion = checkpointSchemaVersion.takeIf { it > 0 } ?: CURRENT_CHECKPOINT_SCHEMA_VERSION
    )

    private companion object {
        const val MAX_TIMELINE_EVENTS = 400
        const val MAX_CONTINUATION_INSTRUCTION_CHARS = 2_000
        const val CURRENT_CHECKPOINT_SCHEMA_VERSION = 1
        val RESUMABLE_STATUSES = setOf(
            DurableTaskStatus.PAUSED,
            DurableTaskStatus.PAUSED_BUDGET,
            DurableTaskStatus.READY_FOR_CONTINUATION
        )
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
    PAUSED_BUDGET,
    WAITING_EXTERNAL,
    WAITING_TIMER,
    READY_FOR_CONTINUATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TaskPauseReason {
    USER,
    APPROVAL,
    NETWORK,
    PROVIDER,
    SYSTEM,
    WORKER_INTERRUPTED
}

enum class BudgetPauseReason {
    TASK_DEADLINE_REACHED,
    TASK_TOKEN_BUDGET_EXHAUSTED,
    TASK_COST_BUDGET_EXHAUSTED,
    RUN_BUDGET_EXHAUSTED,
    TOOL_RETRY_BUDGET_EXHAUSTED
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
    WAITING_EXTERNAL,
    WAITING_TIMER,
    COMPLETED,
    FAILED,
    SKIPPED
}

enum class TaskRunStatus {
    RUNNING,
    PAUSED,
    WAITING_EXTERNAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** Persisted provider operation; completion is analyzed by a subsequent run, never replayed. */
data class ExternalOperation(
    val operationId: String,
    val taskId: String,
    val runId: String,
    val stepId: String,
    val externalProvider: String,
    val externalReferenceId: String? = null,
    val externalStatus: ExternalOperationStatus = ExternalOperationStatus.SUBMITTED,
    val startedAt: Long,
    val nextPollAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val resultReference: String? = null,
    val timeoutPolicy: ExternalOperationTimeoutPolicy = ExternalOperationTimeoutPolicy(),
    val retryPolicy: ExternalOperationRetryPolicy = ExternalOperationRetryPolicy(),
    val cancellationState: ExternalOperationCancellationState = ExternalOperationCancellationState.NONE,
    val pollAttemptCount: Int = 0,
    val reconciliationRequiredReason: String? = null
)

data class ExternalOperationTimeoutPolicy(
    val deadlineAt: Long = Long.MAX_VALUE,
    val action: ExternalOperationTimeoutAction = ExternalOperationTimeoutAction.REPORT_FAILURE_TO_TASK
)

data class ExternalOperationRetryPolicy(
    val maxAttempts: Int = 40,
    val initialBackoffMs: Long = 15_000L,
    val maxBackoffMs: Long = 15 * 60_000L
)

enum class ExternalOperationTimeoutAction {
    REPORT_FAILURE_TO_TASK,
    CANCEL_EXTERNAL,
    REQUIRE_USER_ACTION
}

enum class ExternalOperationCancellationState {
    NONE,
    REQUESTED,
    CONFIRMED,
    REJECTED,
    UNKNOWN
}

enum class ExternalOperationStatus {
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** A single connector side-effect intent with a stable replay key and payload hash. */
data class DurableOperation(
    val operationId: String,
    val taskId: String,
    val runId: String,
    val stepId: String,
    val actionId: String,
    val idempotencyKey: String,
    val payloadSha256: String,
    val provider: String,
    val status: DurableOperationStatus = DurableOperationStatus.PREPARED,
    val providerReference: String? = null,
    val safeError: String = "",
    val preparedAtMs: Long = -1L,
    val completedAtMs: Long = -1L,
    val updatedAtMs: Long = preparedAtMs,
)

enum class DurableOperationStatus {
    PREPARED,
    CLAIMED,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, UNKNOWN)
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
    EXTERNAL_OPERATION_STARTED,
    EXTERNAL_OPERATION_CHECKED,
    EXTERNAL_OPERATION_COMPLETED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_PAUSED,
    TASK_BUDGET_PAUSED,
    TASK_CANCELLED,
    CONTINUITY_MERGED
}

sealed class ContinuityMergeResult {
    data class Merged(val remoteWasRunning: Boolean) : ContinuityMergeResult()
    data object UnknownTask : ContinuityMergeResult()
    data object LocalNewer : ContinuityMergeResult()
    data object LocalExecutionActive : ContinuityMergeResult()
    data object RemoteRequiresLocalReconciliation : ContinuityMergeResult()
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
