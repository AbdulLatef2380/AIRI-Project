package com.airi.assistant.agent.durable

/** Explicit user intent; a free-form message must not silently resume another task. */
sealed interface TaskContinuityCommand {
    val messageId: String

    data class NewMission(
        override val messageId: String,
        val task: DurableTask,
    ) : TaskContinuityCommand

    data class ContinueExistingTask(
        override val messageId: String,
        val taskId: String,
        val expectedStateVersion: Long,
        val userInstruction: String = "",
    ) : TaskContinuityCommand
}

sealed interface TaskContinuityDecision {
    data class CreateNewMission(val task: DurableTask) : TaskContinuityDecision
    data class ResumeExistingTask(
        val task: DurableTask,
        val userInstruction: String,
    ) : TaskContinuityDecision
    data class Rejected(val reason: RejectionReason) : TaskContinuityDecision
}

enum class RejectionReason {
    EMPTY_MESSAGE_ID,
    TASK_NOT_FOUND,
    STALE_STATE_VERSION,
    TASK_NOT_RESUMABLE,
    UNKNOWN_EXTERNAL_OUTCOME,
    INVALID_NEW_MISSION,
    PERSISTENCE_FAILED
}

/** Pure command gate used by UI/use-cases before calling DurableTaskManager. */
object TaskContinuityCommandGate {
    fun decide(command: TaskContinuityCommand, tasks: Map<String, DurableTask>): TaskContinuityDecision = when (command) {
        is TaskContinuityCommand.NewMission -> {
            if (command.messageId.isBlank() || command.task.id.isBlank() || command.task.status != DurableTaskStatus.QUEUED) {
                TaskContinuityDecision.Rejected(RejectionReason.INVALID_NEW_MISSION)
            } else {
                TaskContinuityDecision.CreateNewMission(command.task)
            }
        }
        is TaskContinuityCommand.ContinueExistingTask -> {
            if (command.messageId.isBlank()) return TaskContinuityDecision.Rejected(RejectionReason.EMPTY_MESSAGE_ID)
            val task = tasks[command.taskId] ?: return TaskContinuityDecision.Rejected(RejectionReason.TASK_NOT_FOUND)
            if (task.stateVersion != command.expectedStateVersion) return TaskContinuityDecision.Rejected(RejectionReason.STALE_STATE_VERSION)
            if (task.status !in setOf(DurableTaskStatus.PAUSED, DurableTaskStatus.PAUSED_BUDGET, DurableTaskStatus.READY_FOR_CONTINUATION)) {
                return TaskContinuityDecision.Rejected(RejectionReason.TASK_NOT_RESUMABLE)
            }
            if (task.externalOperations.any { it.externalStatus == ExternalOperationStatus.UNKNOWN }) {
                return TaskContinuityDecision.Rejected(RejectionReason.UNKNOWN_EXTERNAL_OUTCOME)
            }
            TaskContinuityDecision.ResumeExistingTask(task, command.userInstruction)
        }
    }
}
