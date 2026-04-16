package com.airi.assistant.ai.agent

import android.content.Context
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.tools.ToolExecutor

data class TaskExecutionResult(
    val success: Boolean,
    val summary: String,
    val stepResults: List<StepResult>
)

data class StepResult(
    val toolName: String,
    val success: Boolean,
    val data: String,
    val error: String? = null
)

class TaskExecutor(context: Context) {

    private val toolExecutor = ToolExecutor(context)
    private val traceManager = AgentTraceManager.instance

    companion object {
        private const val MAX_STEPS = 3
    }

    suspend fun execute(task: Task, traceId: String? = null): TaskExecutionResult {
        val results = mutableListOf<StepResult>()
        var previousData = ""

        task.steps.take(MAX_STEPS).forEachIndexed { index, step ->
            val params    = resolveParams(step, previousData)
            val stepStart = System.currentTimeMillis()

            val toolResult = try {
                toolExecutor.execute(ToolCall(step.toolName, params))
            } catch (e: Exception) {
                val err = e.message ?: "Unknown error"
                traceId?.let { tid ->
                    traceManager.addStep(tid, AgentStep(
                        stepIndex    = index,
                        type         = AgentStepType.TASK_STEP,
                        name         = step.toolName,
                        inputParams  = params,
                        outputSummary = "",
                        success      = false,
                        error        = err,
                        durationMs   = System.currentTimeMillis() - stepStart
                    ))
                }
                results.add(StepResult(step.toolName, false, "", err))
                return@forEachIndexed
            }

            traceId?.let { tid ->
                traceManager.addStep(tid, AgentStep(
                    stepIndex    = index,
                    type         = AgentStepType.TASK_STEP,
                    name         = step.toolName,
                    inputParams  = params,
                    outputSummary = toolResult.data.take(200),
                    success      = toolResult.success,
                    error        = toolResult.error,
                    durationMs   = System.currentTimeMillis() - stepStart
                ))
            }

            results.add(StepResult(
                toolName = step.toolName,
                success  = toolResult.success,
                data     = toolResult.data,
                error    = toolResult.error
            ))

            if (!toolResult.success) return@forEachIndexed
            previousData = toolResult.data
        }

        val allSuccess = results.isNotEmpty() && results.all { it.success }
        return TaskExecutionResult(
            success     = allSuccess,
            summary     = buildSummary(results),
            stepResults = results
        )
    }

    private fun resolveParams(step: TaskStep, previousData: String): Map<String, String> {
        val resolved = step.params.toMutableMap()
        if (step.toolName == "telegram_send_message"
            && resolved["text"].isNullOrBlank()
            && previousData.isNotBlank()
        ) {
            resolved["text"] = previousData.take(500)
        }
        return resolved
    }

    private fun buildSummary(results: List<StepResult>): String {
        if (results.isEmpty()) return "No steps were executed."
        return buildString {
            val succeeded = results.count { it.success }
            append("Agent completed $succeeded/${results.size} step(s):\n\n")
            results.forEachIndexed { i, r ->
                append("• Step ${i + 1} [${r.toolName.replace("_", " ")}]: ")
                if (r.success) {
                    val preview = r.data.take(300)
                    append(if (r.data.length > 300) "$preview…" else preview)
                } else {
                    append("Failed — ${r.error ?: "Unknown error"}")
                }
                if (i < results.lastIndex) append("\n\n")
            }
        }
    }
}
