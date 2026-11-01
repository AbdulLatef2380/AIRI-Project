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
        val admitted = intent.requiresTools || attachmentCount > 0
        val reason = when {
            attachmentCount > 0 -> "attachment_requires_processing"
            intent.requiresTools -> "explicit_capability_intent"
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
