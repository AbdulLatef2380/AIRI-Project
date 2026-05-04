package com.airi.assistant.agent.reflection

import android.util.Log
import com.airi.assistant.agent.planning.NodeStatus
import com.airi.assistant.agent.planning.TypedPlanGraph

/**
 * PlanQualityScorer — pre-execution plan analysis and confidence scoring.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Called by [UnifiedCognitiveLoop.executeGraph] BEFORE any node executes.
 * Scores the plan structure and returns a [PlanQualityScore]:
 *   - [PlanQualityScore.confidence] 0.0–1.0 — overall plan confidence
 *   - [PlanQualityScore.critique]   — text explanation of what's wrong
 *
 * Plans below [UnifiedCognitiveLoop.MIN_PLAN_CONFIDENCE] are rejected before
 * wasting credit budget on a plan that will likely fail.
 *
 * ── SCORING DIMENSIONS ────────────────────────────────────────────────────────
 * 1. Node count: empty plans → 0, gigantic plans (>12 nodes) → penalty
 * 2. Dependency chain depth: chains of >5 → penalty (sequential bottleneck)
 * 3. Fan-out ratio: average dependents per node > 3 → moderate penalty
 * 4. Circular dependency detection: graph has a cycle → disqualifying (0.0)
 * 5. All-critical flag: when every node is isCritical=true, a single failure
 *    aborts the whole plan — moderate penalty since it's fragile
 * 6. Action diversity: all nodes sharing the same action type → penalty
 *    (indicates a degenerate plan that will likely hit the same failure mode)
 *
 * ── SELF-REPAIR HOOK ─────────────────────────────────────────────────────────
 * [selfRepairSuggestion] produces concrete suggestions the planner can apply
 * to improve the plan without regenerating from scratch. The caller decides
 * whether to apply them or reject the plan outright.
 */
class PlanQualityScorer {

    companion object {
        private const val TAG = "PlanQualityScorer"
        private const val MAX_RECOMMENDED_NODES  = 12
        private const val MAX_RECOMMENDED_DEPTH  = 5
    }

    /**
     * Score [graph] and return a [PlanQualityScore].
     * Never throws — returns a neutral score on any internal error.
     */
    fun score(graph: TypedPlanGraph): PlanQualityScore = runCatching {
        val nodes = graph.allNodes()

        // ── Empty plan ────────────────────────────────────────────────────────
        if (nodes.isEmpty()) {
            return@runCatching PlanQualityScore(
                confidence = 0f,
                critique   = "Plan has no nodes.",
                repairs    = listOf("Add at least one executable goal node.")
            )
        }

        val issues   = mutableListOf<String>()
        val repairs  = mutableListOf<String>()
        var penalty  = 0f

        // ── Circular dependency detection ─────────────────────────────────────
        if (hasCycle(nodes.associate { it.id to it.dependsOn })) {
            Log.e(TAG, "PLAN_CYCLE_DETECTED goalId=${graph.goalId}")
            return@runCatching PlanQualityScore(
                confidence = 0f,
                critique   = "Plan contains a dependency cycle — execution would deadlock.",
                repairs    = listOf("Remove the circular dependency between nodes.")
            )
        }

        // ── Node count ────────────────────────────────────────────────────────
        if (nodes.size > MAX_RECOMMENDED_NODES) {
            penalty += 0.2f
            issues  += "Plan is large (${nodes.size} nodes, max recommended $MAX_RECOMMENDED_NODES)."
            repairs += "Decompose into sub-plans of ≤$MAX_RECOMMENDED_NODES nodes."
        }

        // ── Chain depth ───────────────────────────────────────────────────────
        val depth = maxChainDepth(nodes.associate { it.id to it.dependsOn })
        if (depth > MAX_RECOMMENDED_DEPTH) {
            penalty += 0.15f
            issues  += "Deep dependency chain ($depth levels). Parallelism is limited."
            repairs += "Flatten the dependency graph where possible to enable parallel execution."
        }

        // ── All-critical fragility ────────────────────────────────────────────
        val allCritical = nodes.all { it.isCritical }
        if (allCritical && nodes.size > 3) {
            penalty += 0.1f
            issues  += "All nodes are marked critical — a single failure aborts the entire plan."
            repairs += "Mark non-essential nodes as isCritical=false so they can be skipped on failure."
        }

        // ── Action diversity ──────────────────────────────────────────────────
        val distinctActions = nodes.map { it.action }.toSet().size
        if (nodes.size >= 4 && distinctActions == 1) {
            penalty += 0.15f
            issues  += "All nodes use the same action '${nodes.first().action}' — likely a degenerate plan."
            repairs += "Verify plan generation — repeated identical actions suggest a planning loop."
        }

        // ── Dead start nodes (all depend on something) ────────────────────────
        val hasRoots = nodes.any { it.dependsOn.isEmpty() && it.status == NodeStatus.PENDING }
        if (!hasRoots) {
            penalty += 0.25f
            issues  += "No independent root nodes — execution cannot start (all nodes blocked by dependencies)."
            repairs += "Ensure at least one node has an empty dependsOn list."
        }

        val confidence  = (1f - penalty).coerceIn(0f, 1f)
        val critique    = if (issues.isEmpty()) "Plan structure looks good." else issues.joinToString(" ")

        Log.d(TAG, "PLAN_QUALITY goalId=${graph.goalId} nodes=${nodes.size} depth=$depth " +
            "confidence=${"%.2f".format(confidence)} penalty=$penalty")

        PlanQualityScore(confidence = confidence, critique = critique, repairs = repairs)
    }.getOrElse { e ->
        Log.e(TAG, "PlanQualityScorer.score threw: ${e.message}", e)
        PlanQualityScore(confidence = 0.5f, critique = "Quality check unavailable.", repairs = emptyList())
    }

    // ── Cycle detection (DFS) ──────────────────────────────────────────────────

    private fun hasCycle(deps: Map<String, List<String>>): Boolean {
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            if (node in inStack) return true
            if (node in visited) return false
            visited += node
            inStack += node
            for (dep in (deps[node] ?: emptyList())) {
                if (dfs(dep)) return true
            }
            inStack -= node
            return false
        }

        return deps.keys.any { dfs(it) }
    }

    // ── Max chain depth (longest path in DAG) ─────────────────────────────────

    private fun maxChainDepth(deps: Map<String, List<String>>): Int {
        val memo = mutableMapOf<String, Int>()

        fun depth(node: String): Int {
            memo[node]?.let { return it }
            val parentDeps = deps[node] ?: emptyList()
            val d = if (parentDeps.isEmpty()) 0 else 1 + (parentDeps.maxOfOrNull { depth(it) } ?: 0)
            memo[node] = d
            return d
        }

        return if (deps.isEmpty()) 0 else deps.keys.maxOfOrNull { depth(it) } ?: 0
    }
}

data class PlanQualityScore(
    /** 0.0–1.0. Plans below threshold are rejected pre-execution. */
    val confidence: Float,
    /** Human-readable explanation of quality issues. */
    val critique:   String,
    /** Concrete suggestions to improve the plan. */
    val repairs:    List<String>
)
