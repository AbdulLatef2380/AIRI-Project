package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace

/** Pure AgentTrace → rows projection. Previews are capped; steps are never stored whole. */
object AgentRunMapper {
    /** Default preview cap in characters; callers may lower it from developer settings. */
    const val DEFAULT_PREVIEW_CAP = 600

    /** Cap for the full request and answer kept on the run row (characters). */
    const val FULL_TEXT_CAP = 16_000

    private val FILE_HINTS = listOf("file", "write", "read_", "edit", "document", "pdf", "docx", "xlsx", "csv")
    private val COMMAND_HINTS = listOf("command", "shell", "terminal", "bash", "exec", "run_", "python", "compile")

    fun status(trace: AgentTrace): String = when {
        trace.inProgress -> "RUNNING"
        trace.success -> "COMPLETED"
        trace.finalResult.contains("cancel", ignoreCase = true) && trace.steps.none { it.error != null } -> "CANCELLED"
        else -> "FAILED"
    }

    fun category(step: AgentStep): String {
        val n = step.name.lowercase()
        return when (step.type) {
            AgentStepType.PLAN -> "PLAN"
            AgentStepType.INTENT, AgentStepType.VERIFY -> "SYSTEM"
            AgentStepType.TASK_STEP -> "TASK"
            AgentStepType.MODEL_TURN -> "MODEL"
            AgentStepType.SKILL, AgentStepType.TOOL -> when {
                FILE_HINTS.any(n::contains) -> "FILE"
                COMMAND_HINTS.any(n::contains) -> "COMMAND"
                else -> "TOOL"
            }
        }
    }

    fun toRun(trace: AgentTrace, previewCap: Int = DEFAULT_PREVIEW_CAP): AgentRunEntity {
        val cats = trace.steps.map(::category)
        val last = trace.steps.maxOfOrNull { it.timestampMs } ?: 0L
        return AgentRunEntity(
            id = trace.id,
            title = trace.originalInput.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(200),
            status = status(trace),
            startedAtMs = trace.timestamp,
            endedAtMs = if (trace.inProgress) null else maxOf(trace.timestamp, last),
            model = trace.resolvedModel ?: trace.model,
            provider = trace.resolvedProvider ?: trace.provider,
            fileCount = cats.count { it == "FILE" },
            commandCount = cats.count { it == "COMMAND" },
            toolCount = cats.count { it == "TOOL" },
            resultPreview = trace.finalResult.replace(Regex("\\s+"), " ").trim().take(previewCap),
            request = trace.originalInput.take(FULL_TEXT_CAP),
            answer = trace.finalResult.take(FULL_TEXT_CAP),
            executionId = trace.executionId,
            sessionId = trace.sessionId,
            messageId = trace.messageId,
        )
    }

    fun toEvents(trace: AgentTrace, previewCap: Int = DEFAULT_PREVIEW_CAP): List<AgentRunEventEntity> =
        trace.steps.mapIndexed { index, step ->
            val output = step.error ?: step.outputSummary
            AgentRunEventEntity(
                eventId = "${trace.id}:${step.stepIndex}:$index",
                runId = trace.id,
                sequence = index.toLong(),
                timestampMs = if (step.timestampMs > 0) step.timestampMs else trace.timestamp,
                parentId = step.parentId,
                category = category(step),
                type = step.type.name,
                title = step.name,
                status = if (step.success && step.error == null) "COMPLETED" else "FAILED",
                durationMs = step.durationMs.takeIf { it > 0 },
                errorCode = step.error?.let { "tool_error" },
                inputPreview = step.inputParams.entries.joinToString("; ") { "${it.key}=${it.value}" }.take(previewCap),
                outputPreview = output.take(previewCap),
                // Output beyond the cap is not kept in the row; the workspace writer owns blobs.
                blobRef = null,
            )
        }
}
