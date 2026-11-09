package com.airi.assistant.agent.request

import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Request-boundary service. It owns no UI state and is the only adapter that turns
 * provider/runtime facts into the canonical [RequestPlan] consumed by the loop.
 */
class AgentRequestPlanningService(
    private val supportResolver: (String) -> ModelToolSupport = { providerId ->
        ModelToolSupport.resolve(providerId = providerId)
    },
) {
    fun plan(
        text: String,
        providerId: String,
        availableTools: Collection<ToolSchema>,
    ): RequestPlan = AgentRequestPlanner.plan(
        text = text,
        support = supportResolver(providerId),
        availableTools = availableTools.mapTo(linkedSetOf()) { it.name },
    )
}
