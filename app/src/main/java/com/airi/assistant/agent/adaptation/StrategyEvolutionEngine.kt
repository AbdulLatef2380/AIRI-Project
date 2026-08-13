package com.airi.assistant.agent.adaptation

import android.util.Log
import com.airi.assistant.agent.planning.RecoveryBranch

/**
 * StrategyEvolutionEngine — tracks recovery strategy effectiveness and
 * evolves recovery branch preferences over time.
 *
 * ── WHAT IT DOES ─────────────────────────────────────────────────────────────
 * Each time a node executes and the recovery system makes a decision, this
 * engine records whether that strategy was effective. Over time, strategies
 * with higher effectiveness scores are promoted; poor strategies are demoted.
 *
 * Example evolution:
 *   - Plan A: Retry branch used for "open_app" → succeeded on attempt 2 → Retry score ↑
 *   - Plan B: Retry branch used for "open_app" → failed all 3 attempts → Retry score ↓
 *   - Plan C: "open_app" now uses Skip branch (demoted from Retry) → plan completes faster
 *
 * ── SCORING MODEL ────────────────────────────────────────────────────────────
 * Scores are stored in [PersistentLearningStore] via EMA — they persist across
 * restarts and gradually converge toward the system's actual reliability profile.
 *
 * Promotion threshold: score > 0.70 → prefer this strategy
 * Demotion threshold:  score < 0.30 → avoid this strategy
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 * Called by [PlannerAdaptationEngine.ingest()] for each node result.
 * [getBestRecoveryBranch()] is called by [PlannerAdaptationEngine.getHints()]
 * to populate [PlanAdaptationHints.recoveryBranchOverrides].
 */
class StrategyEvolutionEngine(private val store: PersistentLearningStore) {

    companion object {
        private const val TAG                = "StrategyEvolutionEngine"
        private const val PROMOTION_THRESHOLD = 0.70f
        private const val DEMOTION_THRESHOLD  = 0.30f
    }

    /**
     * Record the outcome of a node execution, including which recovery branch
     * was active for that node and how many attempts were needed.
     *
     * A node that succeeded on attempt 1 scores its branch as fully effective.
     * A node that needed multiple retries scores partially (retry "worked" but
     * was costly). A node that failed entirely scores its branch as ineffective.
     */
    fun recordNodeOutcome(
        actionType:     String,
        recoveryBranch: String,
        attempts:       Int,
        success:        Boolean
    ) {
        // Did the strategy (whatever branch was used) ultimately succeed?
        store.recordStrategyOutcome(recoveryBranch, success)

        // Specifically track retry effectiveness: multi-attempt success is good
        // but multi-attempt failure means retry is a waste for this action type
        if (attempts > 1) {
            store.recordStrategyOutcome("Retry_effective", success)
        }

        // If it succeeded on first attempt, record as strong positive
        if (attempts == 1 && success) {
            store.recordStrategyOutcome("FirstAttempt_success", true)
        }

        Log.d(TAG, "NODE_OUTCOME action=$actionType branch=$recoveryBranch " +
            "attempts=$attempts success=$success")
    }

    /**
     * Returns the best [RecoveryBranch] for [actionType] given all accumulated
     * strategy scores and the action's historical failure rate.
     *
     * Logic:
     *   1. High failure rate + Retry not working → prefer Skip (don't waste budget)
     *   2. Retry score is strong → prefer Retry with 3 attempts
     *   3. Retry score is marginal → Retry with 2 attempts (conservative)
     *   4. High failure rate + Fallback is strong → prefer Fallback to "search" fallback
     *   5. Default → Retry(2)
     */
    fun getBestRecoveryBranch(actionType: String): RecoveryBranch {
        val failureRate   = store.getActionFailureRate(actionType)
        val retryScore    = store.getStrategyScore("Retry")
        val skipScore     = store.getStrategyScore("Skip")
        val fallbackScore = store.getStrategyScore("Fallback")
        val sampleCount   = store.getActionSampleCount(actionType)

        // Not enough data → conservative default
        if (sampleCount < 3) return RecoveryBranch.Retry(maxAttempts = 2)

        return when {
            // Systematically failing + retry is demonstrably not helping → Skip
            failureRate > 0.65f && retryScore < DEMOTION_THRESHOLD ->
                RecoveryBranch.Skip

            // Systematically failing + fallback has good track record → Fallback
            failureRate > 0.55f && fallbackScore > PROMOTION_THRESHOLD ->
                RecoveryBranch.Fallback(fallbackAction = "conversation")

            // Retry is working well → full 3-attempt budget
            retryScore > PROMOTION_THRESHOLD ->
                RecoveryBranch.Retry(maxAttempts = 3)

            // Retry is marginally effective → reduce to 2 attempts to save budget
            retryScore > 0.50f ->
                RecoveryBranch.Retry(maxAttempts = 2)

            // Retry has poor track record, Skip is better → Skip
            retryScore < DEMOTION_THRESHOLD && skipScore > retryScore ->
                RecoveryBranch.Skip

            // Default
            else -> RecoveryBranch.Retry(maxAttempts = 2)
        }.also { branch ->
            Log.d(TAG, "RECOVERY_BRANCH_SELECTED action=$actionType branch=${branch::class.simpleName} " +
                "failureRate=${"%.2f".format(failureRate)} retryScore=${"%.2f".format(retryScore)}")
        }
    }

    /**
     * Map of all strategy names to their current effectiveness scores.
     * Used for observability and audit logging.
     */
    fun getStrategyScores(): Map<String, Float> = store.getAllStrategyScores()

    fun logEvolutionState() {
        val scores = getStrategyScores()
        Log.i(TAG, "AIRI_RUNTIME STRATEGY_EVOLUTION " +
            scores.entries.joinToString(" ") { "${it.key}=${"%.2f".format(it.value)}" } +
            " overall_confidence=${"%.2f".format(store.getOverallConfidence())}")
    }
}
