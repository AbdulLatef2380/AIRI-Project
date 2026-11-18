package com.airi.assistant.agent.loop

import com.airi.assistant.agent.loop.tool.AgentLoopSideEffectPolicy
import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Pure admission boundary for the AgentLoop tool surface.
 *
 * Keeping this decision independent from model calls makes the safety contract
 * testable: durable side effects cannot enter the loop without their typed
 * execution context, and terminal execution remains explicitly gated.
 */
object AgentLoopToolAdmission {
    /**
     * Built-in artifact transforms are registered and dispatched, but are not yet admitted by
     * the typed durable-artifact route. T8 keeps this exception explicit so a newly added tool
     * cannot disappear from the AgentLoop silently.
     */
    val INTENTIONALLY_BLOCKED_WITHOUT_TYPED_ROUTE: Set<String> = setOf(
        "generate_document",
        "generate_spreadsheet",
        "generate_pdf",
        "generate_image",
        "generate_audio",
        "generate_video",
        "compress_files",
    )

    fun filter(
        tools: List<ToolSchema>,
        hasExecutionContext: Boolean,
        hasCalendarRuntime: Boolean,
        terminalEnabled: Boolean,
    ): List<ToolSchema> = tools.filter { schema ->
        val typedContextAvailable = when (schema.name) {
            "calendar_create" -> hasExecutionContext && hasCalendarRuntime
            in AgentLoopTaskRuntime.DURABLE_TOOLS -> hasExecutionContext
            else -> false
        }
        val sideEffectAllowed = AgentLoopSideEffectPolicy.decide(schema, typedContextAvailable) !=
            AgentLoopSideEffectPolicy.Decision.DURABLE_CONTEXT_REQUIRED
        val terminalAllowed = schema.name != "terminal_execute" || terminalEnabled
        sideEffectAllowed && terminalAllowed
    }
}
