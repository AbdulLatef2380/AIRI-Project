package com.airi.assistant.ui.screens

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus

/**
 * Chooses an execution owner for a local project-file edit.
 *
 * A file proposal must bind to one explicit running task/run/step. When a
 * project has zero or multiple eligible tasks, the UI must not silently select
 * whichever task happens to appear first in persisted storage.
 */
internal object ProjectFileEditTaskSelector {

    data class Selection(val eligibleTasks: List<DurableTask>) {
        val task: DurableTask?
            get() = eligibleTasks.singleOrNull()

        val eligibleTaskCount: Int
            get() = eligibleTasks.size

        val requiresExplicitTaskChoice: Boolean
            get() = eligibleTaskCount > 1
    }

    fun select(tasks: List<DurableTask>, projectId: String?): Selection {
        if (projectId.isNullOrBlank()) return Selection(emptyList())
        return Selection(
            tasks.filter { task ->
                task.projectId == projectId &&
                    task.status == DurableTaskStatus.RUNNING &&
                    !task.currentRunId.isNullOrBlank() &&
                    !task.currentStepId.isNullOrBlank()
            }
        )
    }
}
