package com.airi.assistant.agent.reflection

import android.util.Log
import com.airi.assistant.agent.planning.GraphSnapshot
import com.airi.assistant.core.NodeExecutionRecord

/**
 * ExecutionReflector — post-execution self-critique and failure pattern analysis.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Pure functional: takes the completed node results and graph snapshot, produces
 * a [ReflectionReport] with:
 *   - Per-action-type success rates (detects systematically failing action types)
 *   - Execution confidence score (0.0–1.0)
 *   - Self-critique text (human-readable failure analysis)
 *   - Failure mode classification (which failure patterns dominated this run)
 *   - Recommendations for the next planning cycle
 *
 * ── INTEGRATION ───────────────────────────────────────────────────────────────
 * Called by [UnifiedCognitiveLoop.executeGraph] immediately after graph
 * completion. The [ReflectionReport] is stored in [GraphExecutionResult.reflection]
 * and can be fed back into [PlanGenerator] to influence the next plan.
 *
 * ── ADAPTIVE LEARNING ─────────────────────────────────────────────────────────
 * [reflectionHistory] accumulates per-action-type failure counters across
 * execution runs (in-process, not persisted — [ReinforcementMemory] handles
 * cross-session persistence). This allows the reflector to detect that, e.g.,
 * "open_app" has failed 4 times in the last 5 runs and recommend avoiding it.
 */
class ExecutionReflector {

    companion object {
        private const val TAG = "ExecutionReflector"
        private const val MAX_HISTORY = 50
    }

    /** Rolling per-action-type failure/success counter across runs (in-process). */
    private val actionTypeHistory = mutableMapOf<String, ActionTypeStats>()

    data class ActionTypeStats(
        var successes: Int = 0,
        var failures:  Int = 0
    ) {
        val total: Int get() = successes + failures
        val failureRate: Float get() = if (total == 0) 0f else failures.toFloat() / total
    }

    /**
     * Reflect on a completed (or failed) graph execution.
     *
     * @param nodeResults  All node execution records from the run.
     * @param snapshot     Final graph snapshot.
     * @return             [ReflectionReport] with critique and recommendations.
     */
    fun reflect(
        nodeResults: List<NodeExecutionRecord>,
        snapshot:    GraphSnapshot
    ): ReflectionReport {
        if (nodeResults.isEmpty()) {
            return ReflectionReport(
                executionConfidence = 0f,
                critiqueText        = "No nodes executed — plan may be empty or all nodes were policy-denied.",
                failureModes        = listOf(FailureMode.EMPTY_PLAN),
                recommendations     = listOf("Generate a plan with at least one executable node."),
                actionTypeRates     = emptyMap()
            )
        }

        // ── Update per-action-type history ────────────────────────────────────
        for (record in nodeResults) {
            val stats = actionTypeHistory.getOrPut(record.node.action) { ActionTypeStats() }
            if (record.success) stats.successes++ else stats.failures++
        }
        // Prune to MAX_HISTORY total entries
        if (actionTypeHistory.size > MAX_HISTORY) {
            val oldest = actionTypeHistory.entries.minByOrNull { it.value.total }
            oldest?.let { actionTypeHistory.remove(it.key) }
        }

        val total    = nodeResults.size
        val succeded = nodeResults.count { it.success }
        val failed   = total - succeded

        val successRate       = succeded.toFloat() / total.toFloat()
        val retryRatio        = nodeResults.count { it.message?.startsWith("node_exception") == true }.toFloat() / total
        val policyDenialRatio = nodeResults.count { it.message?.startsWith("policy:") == true }.toFloat() / total

        // ── Failure mode classification ───────────────────────────────────────
        val failureModes = mutableListOf<FailureMode>()
        if (snapshot.failedNodes > 0 && snapshot.failedNodes.toFloat() / snapshot.totalNodes > 0.5f)
            failureModes += FailureMode.HIGH_FAILURE_RATE
        if (policyDenialRatio > 0.2f)
            failureModes += FailureMode.POLICY_OVERREACH
        if (retryRatio > 0.3f)
            failureModes += FailureMode.REPEATED_EXCEPTIONS
        val systematicActionTypes = actionTypeHistory.entries
            .filter { it.value.total >= 3 && it.value.failureRate > 0.6f }
            .map { it.key }
        if (systematicActionTypes.isNotEmpty())
            failureModes += FailureMode.SYSTEMATIC_ACTION_FAILURE

        // ── Execution confidence score ────────────────────────────────────────
        // Blended: success rate (60%) + absence of systematic failures (40%)
        val systematicPenalty = (systematicActionTypes.size * 0.1f).coerceAtMost(0.4f)
        val confidence = (successRate * 0.6f + (1f - policyDenialRatio) * 0.4f - systematicPenalty)
            .coerceIn(0f, 1f)

        // ── Self-critique text ────────────────────────────────────────────────
        val critique = buildString {
            when {
                successRate >= 0.9f -> append("Execution excellent: ${succeded}/${total} nodes succeeded. ")
                successRate >= 0.6f -> append("Execution partial: ${succeded}/${total} nodes succeeded. ")
                else                -> append("Execution poor: only ${succeded}/${total} nodes succeeded. ")
            }
            if (systematicActionTypes.isNotEmpty())
                append("Systematic failures detected for actions: ${systematicActionTypes.joinToString()}. ")
            if (policyDenialRatio > 0)
                append("${(policyDenialRatio * 100).toInt()}% of nodes were policy-denied (credit/permission). ")
            nodeResults.filter { !it.success }.take(2).forEach { rec ->
                append("'${rec.node.action}' failed: ${rec.message?.take(60)}. ")
            }
        }.trim()

        // ── Recommendations ───────────────────────────────────────────────────
        val recommendations = mutableListOf<String>()
        if (policyDenialRatio > 0.2f)
            recommendations += "Reduce plan scope — too many actions are exceeding credit/permission budgets."
        if (systematicActionTypes.isNotEmpty())
            recommendations += "Avoid action types [${systematicActionTypes.joinToString()}] in future plans — high historical failure rate."
        if (snapshot.totalNodes > 8)
            recommendations += "Plan is complex (${snapshot.totalNodes} nodes). Consider decomposing into sub-plans."
        if (failureModes.isEmpty() && successRate < 1f)
            recommendations += "Investigate individual node failures — no systemic pattern detected."

        val report = ReflectionReport(
            executionConfidence = confidence,
            critiqueText        = critique,
            failureModes        = failureModes,
            recommendations     = recommendations,
            actionTypeRates     = actionTypeHistory.mapValues { it.value.failureRate }
        )

        Log.i(TAG, "AIRI_RUNTIME REFLECTION confidence=${"%.2f".format(confidence)} " +
            "success=${succeded}/${total} modes=${failureModes.size} " +
            "systematic=${systematicActionTypes.size}")

        return report
    }

    /** Reset in-process history (e.g. on new session). */
    fun clearHistory() {
        actionTypeHistory.clear()
    }
}

// ── Result types ─────────────────────────────────────────────────────────────

data class ReflectionReport(
    /** 0.0–1.0. Below ~0.35 the next plan should be simplified or rejected. */
    val executionConfidence: Float,
    /** Human-readable self-critique of this execution run. */
    val critiqueText:        String,
    /** Dominant failure modes detected in this run. */
    val failureModes:        List<FailureMode>,
    /** Actionable recommendations for the next planning cycle. */
    val recommendations:     List<String>,
    /** Per-action-type failure rates (across this session's history). */
    val actionTypeRates:     Map<String, Float>
)

enum class FailureMode {
    EMPTY_PLAN,
    HIGH_FAILURE_RATE,
    POLICY_OVERREACH,
    REPEATED_EXCEPTIONS,
    SYSTEMATIC_ACTION_FAILURE
}
