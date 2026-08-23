package com.airi.assistant.agent.loop

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.TaskPlanStep
import com.airi.assistant.agent.durable.TaskScope
import com.airi.assistant.agent.durable.TaskStepStatus
import java.util.UUID

/**
 * Foreground task owner for the first typed AgentLoop write path.
 *
 * A task is created only when the loop requests a supported typed operation. It
 * contains no chat prompt or raw tool arguments: private proposal storage owns
 * those values after this runtime has established task/run/step coordinates.
 * Personal calendar writes are intentionally not admitted here; this first path
 * requires an active project as the explicit ownership boundary.
 */
class AgentLoopTaskRuntime(
    private val durableTaskManager: DurableTaskManager,
    private val projectId: String?,
    private val sourceSessionId: String
) : AgentLoopExecutionContextFactory {

    override fun createFor(toolName: String): AgentLoopExecutionContext? {
        if (toolName != CALENDAR_CREATE) return null
        val resolvedProjectId = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!sourceSessionId.matches(SAFE_IDENTIFIER) || !resolvedProjectId.matches(SAFE_IDENTIFIER)) {
            return null
        }

        val taskId = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()
        val context = AgentLoopExecutionContext(
            taskId = taskId,
            missionId = taskId,
            projectId = resolvedProjectId,
            runId = runId,
            stepId = CALENDAR_CREATE,
            agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
            sourceSessionId = sourceSessionId
        )
        if (!context.isStructurallyValid()) return null

        durableTaskManager.registerInProcess(
            DurableTask(
                id = taskId,
                missionId = taskId,
                projectId = resolvedProjectId,
                title = "Calendar event approval",
                description = "Create one reviewed calendar event for the active project",
                agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
                input = "",
                showNotification = false,
                memoryScope = TaskScope.PROJECT,
                knowledgeScope = TaskScope.PROJECT,
                plan = listOf(
                    TaskPlanStep(
                        id = CALENDAR_CREATE,
                        title = "Create approved calendar event",
                        toolSummary = CALENDAR_CREATE
                    )
                )
            )
        )
        durableTaskManager.beginRun(taskId = taskId, runId = runId, stepId = CALENDAR_CREATE)
        return context.takeIf(::ownsRunningStep)
            ?: run {
                durableTaskManager.markFailed(taskId, "Calendar task context could not be initialized")
                null
            }
    }

    fun ownsRunningStep(context: AgentLoopExecutionContext): Boolean {
        if (!context.isStructurallyValid()) return false
        val task = durableTaskManager.getTask(context.taskId) ?: return false
        return task.missionId == context.missionId &&
            task.projectId == context.projectId &&
            task.status == DurableTaskStatus.RUNNING &&
            task.currentRunId == context.runId &&
            task.currentStepId == context.stepId &&
            task.runs.any { run ->
                run.id == context.runId &&
                    run.taskId == context.taskId &&
                    run.missionId == context.missionId &&
                    run.projectId == context.projectId
            } &&
            task.plan.any { step ->
                step.id == context.stepId &&
                    step.runId == context.runId &&
                    step.status == TaskStepStatus.RUNNING
            }
    }

    companion object {
        const val CALENDAR_CREATE = "calendar_create"
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
