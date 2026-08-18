package com.airi.assistant.agent.adaptation

import android.util.Log
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.core.NodeExecutionRecord

/**
 * FailureIntelligenceEngine — proactive failure pattern detection and
 * graph fragility analysis.
 *
 * ── CAPABILITIES ─────────────────────────────────────────────────────────────
 *
 * 1. FAILURE FINGERPRINTING
 *    Each failure is tagged with a fingerprint = "actionType_errorCategory".
 *    Fingerprints that repeat ≥ [FINGERPRINT_ALARM_THRESHOLD] times are
 *    escalated to the avoid-list and logged as REPEATED_FAILURE_PATTERN.
 *    This detects "open_app always times out" faster than EMA-based trust alone.
 *
 * 2. ROOT-CAUSE CLUSTERING
 *    Failures within a single execution wave are grouped by error category
 *    (network, permission, timeout, etc). A cluster of ≥2 failures in the
 *    same category indicates a systemic issue (e.g. no network) rather than
 *    an isolated action failure. Logged as FAILURE_CLUSTER.
 *
 * 3. CASCADING FAILURE DETECTION
 *    If >70% of failed nodes have at least one failed dependency, this is
 *    a cascade — a root failure propagated downstream. Detected cascades
 *    trigger PREFER_SIMPLE_STEPS to reduce future plan fan-out.
 *
 * 4. UNSAFE PLAN PATTERN RECOGNITION
 *    [computeGraphFragility] scores a set of nodes 0–1 based on:
 *    - Fraction of critical nodes (high = fragile)
 *    - Max fan-in depth (deep dependencies = fragile)
 *    - Avoided actions present in the plan (already-bad actions = fragile)
 *    Plans above the fragility threshold are flagged before execution.
 *
 * 5. AGENT TRUST UPDATE
 *    Each node result updates the trust score for that node's action type
 *    via [PersistentLearningStore.recordAgentOutcome]. Agents that fall
 *    below the quarantine threshold are banned from future routing.
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 * Called by [PlannerAdaptationEngine.ingest()] after every graph execution.
 * [computeGraphFragility()] is called by [PlannerAdaptationEngine.getHints()]
 * to populate [PlanAdaptationHints.graphFragility].
 */
class FailureIntelligenceEngine(private val store: PersistentLearningStore) {

    companion object {
        private const val TAG = "FailureIntelligenceEngine"

        /** Same fingerprint this many times → escalate to avoid list */
        private const val FINGERPRINT_ALARM_THRESHOLD = 3

        /** Fraction of failures that are downstream of another failure → cascade */
        private const val CASCADE_THRESHOLD = 0.70f

        /** Graph fragility above this threshold → flag the plan as unsafe */
        const val FRAGILITY_UNSAFE_THRESHOLD = 0.65f
    }

    /**
     * Full analysis pass over a completed execution's results.
     * Updates all persistent stores and produces log proofs for every
     * detected pattern.
     */
    fun analyze(nodeResults: List<NodeExecutionRecord>) {
        if (nodeResults.isEmpty()) return

        val failed    = nodeResults.filter { !it.success }
        val succeeded = nodeResults.filter { it.success }

        if (failed.isEmpty()) {
            // All succeeded — update positive trust signals
            succeeded.forEach { store.recordAgentOutcome(it.node.action, true) }
            return
        }

        // 1. Failure fingerprinting ────────────────────────────────────────────
        for (record in failed) {
            val fp    = fingerprint(record.node.action, record.message ?: "")
            store.recordFailureFingerprint(fp)
            val count = store.getFailureFingerprintCount(fp)
            if (count >= FINGERPRINT_ALARM_THRESHOLD) {
                Log.w(TAG, "AIRI REPEATED_FAILURE_PATTERN fp=$fp count=$count " +
                    "action=${record.node.action}")
                store.addAvoidedAction(record.node.action)
            }
        }

        // 2. Root-cause clustering ─────────────────────────────────────────────
        val clusters = clusterByCategory(failed)
        for ((category, records) in clusters) {
            if (records.size >= 2) {
                Log.w(TAG, "AIRI FAILURE_CLUSTER category=$category " +
                    "count=${records.size} " +
                    "actions=${records.map { it.node.action }.distinct()}")
                // A network cluster probably means the whole request should be retried later
                // We demote all network-sensitive actions temporarily
                if (category == "network") {
                    records.forEach { store.recordStrategyOutcome("network_action", false) }
                }
            }
        }

        // 3. Cascading failure detection ───────────────────────────────────────
        val failedIds = failed.map { it.node.id }.toSet()
        val cascadeCount = failed.count { record ->
            record.node.dependsOn.any { dep -> dep in failedIds }
        }
        val cascadeRatio = if (failed.size > 1) cascadeCount.toFloat() / failed.size else 0f
        if (cascadeRatio > CASCADE_THRESHOLD && failed.size >= 2) {
            Log.w(TAG, "AIRI CASCADE_DETECTED ratio=${"%.2f".format(cascadeRatio)} " +
                "failed=${failed.size} cascading=$cascadeCount")
            // Cascades → prefer simpler plans with fewer dependencies
            store.setPreferSimpleSteps(true)
        }

        // 4. Agent trust update ────────────────────────────────────────────────
        for (record in nodeResults) {
            store.recordAgentOutcome(record.node.action, record.success)
        }

        Log.i(TAG, "AIRI FAILURE_ANALYSIS failed=${failed.size} " +
            "clusters=${clusters.size} cascadeRatio=${"%.2f".format(cascadeRatio)} " +
            "quarantined=${store.getQuarantinedAgents().size}")
    }

    /**
     * Compute a fragility score (0.0–1.0) for a list of plan nodes.
     *
     * High fragility = the plan is likely to cascade-fail:
     *   - Too many critical nodes (one failure → whole plan aborts)
     *   - Deep fan-in (nodes depend on many predecessors)
     *   - Avoided action types present (known-bad actions in the plan)
     *
     * Plans with fragility > [FRAGILITY_UNSAFE_THRESHOLD] should be simplified
     * before execution.
     */
    fun computeGraphFragility(nodes: List<GoalNode>): Float {
        if (nodes.isEmpty()) return 0f

        val criticalFraction = nodes.count { it.isCritical }.toFloat() / nodes.size
        val maxFanIn         = nodes.maxOfOrNull { it.dependsOn.size } ?: 0
        val fanInFactor      = (maxFanIn.toFloat() / 5f).coerceAtMost(1f)
        val avoided          = store.getAvoidedActions()
        val avoidedFraction  = nodes.count { it.action in avoided }.toFloat() / nodes.size

        val fragility = (criticalFraction * 0.40f +
                         fanInFactor      * 0.30f +
                         avoidedFraction  * 0.30f).coerceIn(0f, 1f)

        if (fragility > FRAGILITY_UNSAFE_THRESHOLD) {
            Log.w(TAG, "AIRI GRAPH_FRAGILE score=${"%.2f".format(fragility)} " +
                "critical=${"%.2f".format(criticalFraction)} " +
                "fanIn=$maxFanIn avoided=$avoidedFraction")
        }

        return fragility
    }

    // ── Internal utilities ────────────────────────────────────────────────────

    /**
     * Compute a stable fingerprint string for a failure.
     * Format: "actionType_errorCategory" — e.g. "open_app_timeout".
     */
    fun fingerprint(actionType: String, errorMessage: String): String {
        val category = when {
            errorMessage.contains("timeout", ignoreCase = true)    -> "timeout"
            errorMessage.contains("permission", ignoreCase = true) -> "permission"
            errorMessage.contains("denied", ignoreCase = true)     -> "permission"
            errorMessage.contains("network", ignoreCase = true) ||
            errorMessage.contains("connect", ignoreCase = true)    -> "network"
            errorMessage.contains("null", ignoreCase = true) ||
            errorMessage.contains("exception", ignoreCase = true)  -> "exception"
            errorMessage.contains("policy", ignoreCase = true)     -> "policy"
            else                                                    -> "unknown"
        }
        return "${actionType}_$category"
    }

    private fun clusterByCategory(
        records: List<NodeExecutionRecord>
    ): Map<String, List<NodeExecutionRecord>> = records.groupBy { record ->
        val msg = record.message?.lowercase() ?: ""
        when {
            msg.contains("timeout")                              -> "timeout"
            msg.contains("permission") || msg.contains("denied") -> "permission"
            msg.contains("network")    || msg.contains("connect") -> "network"
            msg.contains("policy")                               -> "policy"
            else                                                 -> "other"
        }
    }
}
