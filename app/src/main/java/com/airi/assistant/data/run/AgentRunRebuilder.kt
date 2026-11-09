package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace

/**
 * Part 5 §6: rebuilds an [AgentTrace] from the Room rows, so the run screen can open a run that the
 * in-memory trace store no longer holds (older than its retention, or lost with the process).
 *
 * Rows written since schema 2 carry the full request and answer; older rows fall back to the title and
 * the preview. A step's arguments are always the stored capped preview, so the result stays read-only.
 * A run still marked RUNNING has no live producer any more and is shown as an interrupted (failed)
 * run rather than a spinner that never ends.
 */
object AgentRunRebuilder {
    private val PARAM_SPLIT = Regex(";\\s+(?=[A-Za-z_][A-Za-z0-9_]*=)")
    private const val CANCELLED_MARKER = "cancelled"

    fun toTrace(run: AgentRunEntity, events: List<AgentRunEventEntity>): AgentTrace {
        val steps = events.sortedBy { it.sequence }.mapIndexed { index, e -> toStep(e, index) }
        val success = run.status == "COMPLETED"
        // Schema 2 rows carry the full text; older rows fall back to the title and the preview.
        val answerText = run.answer.ifEmpty { run.resultPreview }
        val finalResult = when {
            run.status == "CANCELLED" ->
                answerText.takeIf { it.contains("cancel", ignoreCase = true) } ?: CANCELLED_MARKER
            else -> answerText
        }
        return AgentTrace(
            id = run.id,
            traceId = run.id,
            originalInput = run.request.ifEmpty { run.title },
            steps = steps,
            finalResult = finalResult,
            success = success,
            timestamp = run.startedAtMs,
            executionId = run.executionId,
            messageId = run.messageId,
            sessionId = run.sessionId,
            source = AgentTrace.SOURCE_CHAT,
            model = run.model,
            provider = run.provider,
            resolvedModel = run.model,
            resolvedProvider = run.provider,
            inProgress = false,
        )
    }

    internal fun parseParams(preview: String): Map<String, String> {
        if (preview.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        preview.split(PARAM_SPLIT).forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return out
    }

    private fun toStep(e: AgentRunEventEntity, index: Int): AgentStep {
        val ok = e.status == "COMPLETED"
        return AgentStep(
            stepIndex = index,
            type = AgentStepType.values().firstOrNull { it.name == e.type } ?: AgentStepType.TASK_STEP,
            name = e.title,
            inputParams = parseParams(e.inputPreview),
            outputSummary = if (ok) e.outputPreview else "",
            success = ok,
            error = if (ok) null else e.outputPreview.ifBlank { e.errorCode ?: "tool_error" },
            durationMs = e.durationMs ?: 0L,
            timestampMs = e.timestampMs,
            parentId = e.parentId,
        )
    }
}
