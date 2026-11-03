package com.airi.assistant.agent.loop

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.QueryType
import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Decides whether a request has earned a user-visible execution graph.
 *
 * The agent may still receive tool schemas for analytical turns so the model can
 * choose a capability if the request explicitly requires one. That catalog is
 * not itself evidence that the user asked for a tool, and MAX_STEPS is never a
 * user-visible plan length.
 *
 * Scope (report 1, items 3, 4, 32; stages 18 and 22): this decision only controls the
 * user-visible execution graph. "direct_response" does not remove tools from the
 * request, and tool exposure is decided by the query type and capability policies,
 * not here. An attachment alone no longer earns a graph: its content travels inline
 * with the request, so "summarize this file" is a direct answer. A request that also
 * names a capability (read the screen, search the web, ...) is admitted by that intent.
 *
 * Item 3 (stage 26): a "direct_response" decision is not a lock-out. If the model later
 * calls a tool, AgentLoop publishes the graph at that moment (`isPlanPublished` check
 * before the first tool wave), so entry to the agent path is not decided by keywords alone.
 */
internal object ExecutionAdmissionPolicy {
    data class Decision(
        val admitted: Boolean,
        val plannedStepCount: Int,
        val reason: String,
    )

    fun decide(
        input: String,
        queryType: QueryType,
        effectiveTools: List<ToolSchema>,
        attachmentCount: Int,
    ): Decision {
        val intent = CapabilityIntentDetector.detect(input)
        val admitted = intent.requiresTools
        val reason = when {
            intent.requiresTools -> "explicit_capability_intent"
            attachmentCount > 0 -> "direct_response_with_attachment"
            else -> "direct_response"
        }
        // A count is published only for a known single deterministic step. For
        // all other admitted requests, 0 means "not declared" rather than a
        // fabricated estimate based on the loop ceiling or tool catalog size.
        val plannedStepCount = if (admitted && intent.capabilities.size == 1 &&
            effectiveTools.size == 1 && queryType != QueryType.UNKNOWN
        ) 1 else 0
        return Decision(admitted, plannedStepCount, reason)
    }
}
