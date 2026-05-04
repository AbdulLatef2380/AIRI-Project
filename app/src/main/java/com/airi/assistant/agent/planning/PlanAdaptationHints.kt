package com.airi.assistant.agent.planning

/**
 * PlanAdaptationHints — the learning signal snapshot injected into
 * [PlanGenerator] before every plan generation call.
 *
 * This is the data contract between [PlannerAdaptationEngine] (which computes
 * the hints from all accumulated learning) and [PlanGenerator] (which applies
 * them to filter, simplify, and re-route the plan it generates).
 *
 * ── FIELDS ───────────────────────────────────────────────────────────────────
 * [avoidedActions]          Action types that have historically failed at a
 *                           rate above the avoid threshold. PlanGenerator will
 *                           remove steps of these types and collapse Wait
 *                           sequences.
 *
 * [preferSimple]            When true (overall confidence < 0.50), PlanGenerator
 *                           caps plan depth and disables multi-step chains.
 *
 * [maxStepsHint]            Hard upper bound on plan node count, calibrated from
 *                           the confidence band. null = no cap.
 *
 * [overallConfidence]       The current EMA of execution confidence across all
 *                           recent runs. Used by quality scorers and rejection gates.
 *
 * [quarantinedAgents]       Agent types (maps to GoalNode.action) that are
 *                           temporarily quarantined due to sustained failures.
 *                           Plans containing quarantined agents are flagged.
 *
 * [recoveryBranchOverrides] Per-action-type preferred RecoveryBranch, derived
 *                           from strategy evolution scoring. TypedPlanGraph
 *                           applies these when building nodes.
 *
 * [strategyScores]          Current effectiveness scores for each recovery
 *                           strategy. Exposed for observability / audit.
 */
data class PlanAdaptationHints(
    val avoidedActions:          Set<String>             = emptySet(),
    val preferSimple:            Boolean                 = false,
    val maxStepsHint:            Int?                    = null,
    val overallConfidence:       Float                   = 1.0f,
    val quarantinedAgents:       Set<String>             = emptySet(),
    val recoveryBranchOverrides: Map<String, RecoveryBranch> = emptyMap(),
    val strategyScores:          Map<String, Float>      = emptyMap()
) {
    companion object {
        /** A no-op hint set — behaves exactly as if no learning has occurred. */
        val EMPTY = PlanAdaptationHints()
    }

    val hasConstraints: Boolean
        get() = avoidedActions.isNotEmpty() ||
                preferSimple ||
                maxStepsHint != null ||
                quarantinedAgents.isNotEmpty()
}
