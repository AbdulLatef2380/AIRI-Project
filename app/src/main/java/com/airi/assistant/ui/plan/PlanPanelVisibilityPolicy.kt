package com.airi.assistant.ui.plan

import com.airi.assistant.ui.viewmodel.ExecutionStage

/**
 * Keeps the plan panel contextual instead of turning it into a second chat
 * surface for every request. A panel is useful when the agent has a real
 * multi-step graph, or while recovering/reflection follows such a graph.
 */
object PlanPanelVisibilityPolicy {
    fun shouldShow(
        stage: ExecutionStage,
        nodesTotal: Int,
        stepCount: Int,
        explicitlyPlanned: Boolean = false
    ): Boolean {
        if (explicitlyPlanned) return true
        val hasMultiStepPlan = nodesTotal > 1 || stepCount > 1
        return hasMultiStepPlan && stage in setOf(
            ExecutionStage.PLANNING,
            ExecutionStage.EXECUTING,
            ExecutionStage.RECOVERING,
            ExecutionStage.REFLECTING,
            ExecutionStage.COMPLETED,
            ExecutionStage.FAILED
        )
    }
}
