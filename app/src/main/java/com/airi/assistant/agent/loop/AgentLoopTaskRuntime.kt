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
 * Terminal execution is session-scoped and receives the same durable ownership
 * coordinates, while the interactive UI terminal keeps its own principal.
 */
class AgentLoopTaskRuntime(
    private val durableTaskManager: DurableTaskManager,
    private val projectId: String?,
    private val sourceSessionId: String
) : AgentLoopExecutionContextFactory {

    override fun createFor(toolName: String): AgentLoopExecutionContext? {
        if (toolName !in DURABLE_TOOLS) return null
        val resolvedProjectId = projectId?.trim()?.takeIf { it.isNotEmpty() }
        if (!sourceSessionId.matches(SAFE_IDENTIFIER) ||
            (resolvedProjectId != null && !resolvedProjectId.matches(SAFE_IDENTIFIER))) {
            return null
        }

        val taskId = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()
        val context = AgentLoopExecutionContext(
            taskId = taskId,
            missionId = taskId,
            projectId = resolvedProjectId,
            runId = runId,
            stepId = toolName,
            agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
            sourceSessionId = sourceSessionId
        )
        if (!context.isStructurallyValid()) return null

        durableTaskManager.registerInProcess(
            DurableTask(
                id = taskId,
                missionId = taskId,
                projectId = resolvedProjectId,
                title = "AIRI ${toolName.replace('_', ' ')} approval",
                description = "One reviewed device or resource action for the source session",
                agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
                input = "",
                showNotification = false,
                memoryScope = if (toolName == TERMINAL_EXECUTE || toolName == WRITE_FILE) TaskScope.SESSION else TaskScope.PROJECT,
                knowledgeScope = TaskScope.PROJECT,
                plan = listOf(
                    TaskPlanStep(
                        id = toolName,
                        title = "Execute approved $toolName",
                        toolSummary = toolName
                    )
                )
            )
        )
        if (!durableTaskManager.beginRun(taskId = taskId, runId = runId, stepId = toolName)) {
            return null
        }
        return context.takeIf(::ownsRunningStep)
            ?: run {
                durableTaskManager.markFailed(taskId, "Task context could not be initialized")
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
        const val TERMINAL_EXECUTE = "terminal_execute"
        const val WRITE_FILE = "write_file"
        val ARTIFACT_TOOLS = setOf(
            "generate_document", "generate_spreadsheet", "generate_pdf",
            "generate_image", "generate_image_from_prompt", "generate_audio",
            "generate_video", "compress_files",
        )
        val DURABLE_TOOLS = setOf(
            CALENDAR_CREATE, TERMINAL_EXECUTE, WRITE_FILE,
            "open_app", "tap", "type_text", "scroll_down", "go_back", "flashlight",
            "set_alarm", "create_note",
        ) + ARTIFACT_TOOLS
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
