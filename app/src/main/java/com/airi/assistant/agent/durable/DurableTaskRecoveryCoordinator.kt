package com.airi.assistant.agent.durable

/**
 * Pure recovery planning for the local durable-task store.
 *
 * Recovery is deliberately conservative: a persisted RUNNING task may have
 * crossed a side-effect boundary, so it becomes a resumable pause and is never
 * put back on the generic worker queue automatically. Only tasks already in
 * QUEUED are eligible for idempotent WorkManager reconciliation. External
 * waits and ready continuations remain owned by their dedicated recovery path.
 */
object DurableTaskRecoveryCoordinator {
    private const val PROCESS_INTERRUPTION_REASON =
        "Process ended while this execution run was active; safe continuation is required"

    fun plan(tasks: List<DurableTask>, nowMs: Long = System.currentTimeMillis()): DurableTaskRecoveryPlan {
        val interruptedTasks = tasks.mapNotNull { task ->
            if (task.status != DurableTaskStatus.RUNNING) return@mapNotNull null

            task.pauseAfterWorkerStop(PROCESS_INTERRUPTION_REASON, nowMs)
                .appendTimeline(
                    TaskTimelineEvent(
                        type = TaskTimelineEventType.TASK_PAUSED,
                        summary = "Process recovery paused an interrupted run",
                        detail = "Safe continuation is required before execution resumes",
                        runId = task.currentRunId,
                        stepId = task.currentStepId,
                        recordedAtMs = nowMs
                    )
                )
        }

        val interruptedIds = interruptedTasks.mapTo(mutableSetOf()) { it.id }
        val queuedTaskIds = tasks.asSequence()
            .filter { it.status == DurableTaskStatus.QUEUED && !it.isTerminal }
            .map { it.id }
            .toList()
        val waitingExternalTaskIds = tasks.asSequence()
            .filter {
                it.status == DurableTaskStatus.WAITING_EXTERNAL ||
                    it.status == DurableTaskStatus.WAITING_TIMER
            }
            .map { it.id }
            .toList()

        return DurableTaskRecoveryPlan(
            interruptedTasks = interruptedTasks,
            queuedTaskIds = queuedTaskIds,
            waitingExternalTaskIds = waitingExternalTaskIds,
            untouchedTaskIds = tasks.asSequence()
                .filterNot { it.id in interruptedIds }
                .map { it.id }
                .toList()
        )
    }
}

data class DurableTaskRecoveryPlan(
    /** RUNNING tasks transformed to PAUSED without replaying their current run. */
    val interruptedTasks: List<DurableTask>,
    /** QUEUED tasks safe to pass to unique-work scheduling with KEEP semantics. */
    val queuedTaskIds: List<String>,
    /** External waits requiring their own reconciler, never the generic worker. */
    val waitingExternalTaskIds: List<String>,
    /** Records that needed no lifecycle mutation in this pass. */
    val untouchedTaskIds: List<String>
)

data class DurableTaskRecoverySummary(
    val pausedInterruptedTaskIds: List<String>,
    val eligibleQueuedTaskIds: List<String>,
    val scheduledQueuedTaskIds: List<String>,
    val waitingExternalTaskIds: List<String>
)
