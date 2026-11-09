package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.core.AgentEvent
import com.airi.assistant.core.ExecutionTraceKind

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
                eventId = step.eventId.ifBlank { "${trace.id}:${step.stepIndex}:$index" },
                runId = trace.id,
                sequence = index.toLong(),
                timestampMs = if (step.timestampMs > 0) step.timestampMs else trace.timestamp,
                parentId = step.parentId,
                actionId = step.actionId,
                category = category(step),
                type = step.type.name + (
                    step.lifecycleKinds.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "|", separator = "|")
                        .orEmpty()
                    ),
                title = step.name,
                status = if (step.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED &&
                    (!step.success || step.error != null)
                ) "FAILED" else step.traceStatus.name,
                durationMs = step.durationMs.takeIf { it > 0 },
                errorCode = step.error?.let { "tool_error" },
                inputPreview = step.inputParams.entries.joinToString("; ") { "${it.key}=${it.value}" }.take(previewCap),
                outputPreview = output.take(previewCap),
                // Output beyond the cap is not kept in the row; the workspace writer owns blobs.
                blobRef = null,
            )
        }

    /** Projects one canonical live event without persisting secrets or unbounded payloads. */
    fun toEvent(event: AgentEvent, previewCap: Int = DEFAULT_PREVIEW_CAP): AgentRunEventEntity =
        AgentRunEventEntity(
            eventId = event.eventId,
            runId = traceIdForExecution(event.executionId),
            sequence = event.sequence,
            timestampMs = event.timestampMs,
            parentId = event.parentId,
            actionId = event.actionId,
            category = when (event.type) {
                AgentEvent.Type.INTENT_RESOLVED,
                AgentEvent.Type.TOOLS_EXPOSED,
                AgentEvent.Type.PLAN_UPDATED,
                AgentEvent.Type.VERIFY,
                AgentEvent.Type.APPROVAL,
                AgentEvent.Type.FALLBACK,
                AgentEvent.Type.FINAL,
                AgentEvent.Type.LIFECYCLE -> "SYSTEM"
                AgentEvent.Type.MODEL_TURN -> "MODEL"
                AgentEvent.Type.SKILL_LOADED -> "SKILL"
                AgentEvent.Type.TOOL_CALL,
                AgentEvent.Type.TOOL_RESULT -> "TOOL"
            },
            // Keep the exact source lifecycle when available; the coarse AgentEvent type
            // remains useful for grouping but must not erase requested/output/approval phases.
            type = event.traceKind?.name ?: event.type.name,
            title = event.title.take(previewCap),
            status = when (event.traceKind) {
                ExecutionTraceKind.TOOL_REQUESTED -> "PENDING"
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.EXECUTION_STARTED,
                ExecutionTraceKind.PLAN_CREATED,
                ExecutionTraceKind.REASONING_NOTE,
                ExecutionTraceKind.PLANNING,
                ExecutionTraceKind.STEP_STARTED,
                ExecutionTraceKind.VERIFICATION_STARTED,
                ExecutionTraceKind.REFLECTING,
                ExecutionTraceKind.WAITING_FOR_APPROVAL -> "RUNNING"
                ExecutionTraceKind.RECOVERING -> "RECOVERING"
                ExecutionTraceKind.TOOL_OUTPUT,
                ExecutionTraceKind.TOOL_COMPLETED,
                ExecutionTraceKind.STEP_COMPLETED,
                ExecutionTraceKind.VERIFICATION_PASSED,
                ExecutionTraceKind.COMPLETED -> "COMPLETED"
                ExecutionTraceKind.TOOL_FAILED,
                ExecutionTraceKind.FAILED,
                ExecutionTraceKind.TIMED_OUT,
                ExecutionTraceKind.VERIFICATION_FAILED,
                ExecutionTraceKind.APPROVAL_TIMEOUT -> "FAILED"
                ExecutionTraceKind.TOOL_CANCELLED,
                ExecutionTraceKind.CANCELLED,
                ExecutionTraceKind.APPROVAL_CANCELLED -> "CANCELLED"
                null -> when (event.type) {
                AgentEvent.Type.FINAL -> if (event.errorCode == null) "COMPLETED" else "FAILED"
                AgentEvent.Type.TOOL_RESULT -> if (event.errorCode == null) "COMPLETED" else "FAILED"
                else -> "RECORDED"
                }
            },
            durationMs = event.durationMs,
            errorCode = event.errorCode,
            inputPreview = event.inputParams.entries
                .joinToString("; ") { "${it.key}=${it.value}" }
                .take(previewCap),
            outputPreview = event.detail.orEmpty().take(previewCap),
        )

    fun liveRun(event: AgentEvent): AgentRunEntity = AgentRunEntity(
        id = traceIdForExecution(event.executionId),
        title = event.title.take(200),
        status = when (event.type) {
            AgentEvent.Type.FINAL -> if (event.errorCode == null) "COMPLETED" else "FAILED"
            AgentEvent.Type.APPROVAL -> "WAITING_APPROVAL"
            else -> "RUNNING"
        },
        startedAtMs = event.timestampMs,
        endedAtMs = event.takeIf { it.type == AgentEvent.Type.FINAL }?.timestampMs,
        executionId = event.executionId,
        sessionId = event.sessionId,
    )

    fun traceIdForExecution(executionId: String): String = "trace-$executionId"
}
