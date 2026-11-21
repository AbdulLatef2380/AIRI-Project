package com.airi.assistant.agent.durable

/**
 * Executes explicit task-continuity commands against a single durable snapshot.
 *
 * The gate is deliberately evaluated immediately before the write operation, and
 * the continuation handler must enforce expectedStateVersion again. This keeps a
 * stale UI command from reviving a task after another actor has changed it.
 */
object TaskContinuityCommandExecutor {
    sealed interface Result {
        data class Created(val taskId: String) : Result
        data class Resumed(val taskId: String, val runId: String) : Result
        data class Rejected(val reason: RejectionReason) : Result
    }

    fun execute(
        command: TaskContinuityCommand,
        tasks: Map<String, DurableTask>,
        createNewMission: (DurableTask) -> Boolean,
        resumeExistingTask: (taskId: String, expectedStateVersion: Long, userInstruction: String) -> String?,
    ): Result {
        return when (val decision = TaskContinuityCommandGate.decide(command, tasks)) {
            is TaskContinuityDecision.Rejected -> Result.Rejected(decision.reason)
            is TaskContinuityDecision.CreateNewMission -> {
                if (createNewMission(decision.task)) Result.Created(decision.task.id)
                else Result.Rejected(RejectionReason.PERSISTENCE_FAILED)
            }
            is TaskContinuityDecision.ResumeExistingTask -> {
                val runId = resumeExistingTask(
                    decision.task.id,
                    decision.task.stateVersion,
                    decision.userInstruction,
                )
                if (runId == null) Result.Rejected(RejectionReason.PERSISTENCE_FAILED)
                else Result.Resumed(decision.task.id, runId)
            }
        }
    }
}
