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
    /** Built-ins intentionally unavailable until a real runtime route is added. */
    val INTENTIONALLY_BLOCKED_WITHOUT_TYPED_ROUTE: Set<String> = emptySet()

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
