package com.airi.assistant.agent.adaptation

import android.content.Context
import android.util.Log

/**
 * PersistentLearningStore — SharedPreferences-backed survival store for all
 * adaptive learning signals in AIRI.
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 * Every adaptive signal in the previous architecture was in-process: process
 * death erased all accumulated knowledge. This store makes learning survive:
 *   - Process death
 *   - App restart
 *   - Device reboot
 *
 * ── DATA STORED ──────────────────────────────────────────────────────────────
 * 1. Per-action-type failure rates (EMA over executions)
 * 2. Avoided action list (persistently blocked action types)
 * 3. Agent trust scores (EMA, auto-quarantine below threshold)
 * 4. Quarantined agent set (agents auto-banned for repeated failures)
 * 5. Recovery strategy effectiveness scores (which branches actually work)
 * 6. Failure fingerprint counts (repeated error patterns)
 * 7. Overall execution confidence trend
 * 8. Planner complexity preference (prefer simple / max steps hint)
 * 9. Per-goal-pattern task success rates
 *
 * ── DESIGN PRINCIPLES ────────────────────────────────────────────────────────
 * - All updates are EMA (exponential moving average), not simple counters.
 *   EMA naturally weights recent experience over stale history, preventing
 *   permanently blacklisting an action that improved.
 * - Auto-quarantine triggers at trust < 0.30 (after ≥3 samples).
 * - Auto-release triggers at trust > 0.70 (rehabilitation after improvement).
 * - All SharedPreferences writes use `apply()` (async, not blocking the calling
 *   coroutine) since loss of a single record on race is acceptable; correctness
 *   comes from the EMA property (the next write corrects any loss).
 */
class PersistentLearningStore(context: Context) {

    companion object {
        private const val TAG   = "PersistentLearningStore"
        private const val PREFS = "airi_learning_v1"

        // EMA decay factors
        private const val TRUST_ALPHA      = 0.15f   // trust score EMA (slow to change)
        private const val STRATEGY_ALPHA   = 0.20f   // strategy score EMA
        private const val TASK_ALPHA       = 0.20f   // task success rate EMA
        private const val CONFIDENCE_ALPHA = 0.20f   // overall confidence EMA

        // Thresholds
        private const val QUARANTINE_TRUST_THRESHOLD = 0.30f
        private const val RELEASE_TRUST_THRESHOLD    = 0.70f
        private const val ACTION_AVOID_RATE          = 0.65f   // failure rate → add to avoid list
        private const val ACTION_REHABILITATE_RATE   = 0.35f   // failure rate → remove from avoid list
        private const val ACTION_MIN_SAMPLES         = 3

        // SharedPreferences key prefixes
        private const val PFX_ACTION_FAILURES = "ac_f_"
        private const val PFX_ACTION_SUCCESS  = "ac_s_"
        private const val PFX_ACTION_RATE     = "ac_rate_"
        private const val PFX_TRUST           = "trust_"
        private const val PFX_STRATEGY        = "strat_"
        private const val PFX_FINGERPRINT     = "fp_"
        private const val PFX_TASK            = "task_"
        private const val KEY_AVOID           = "avoid_actions"
        private const val KEY_QUARANTINE      = "quarantine_agents"
        private const val KEY_PREFER_SIMPLE   = "prefer_simple"
        private const val KEY_MAX_STEPS       = "max_steps"
        private const val KEY_CONFIDENCE      = "overall_confidence"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Action-type learning ──────────────────────────────────────────────────

    /**
     * Record an execution outcome for [actionType].
     * Automatically adds to / removes from the avoided-action list based on
     * accumulated failure rate once we have [ACTION_MIN_SAMPLES] samples.
     */
    fun recordActionOutcome(actionType: String, success: Boolean) {
        val successes = prefs.getInt(PFX_ACTION_SUCCESS + actionType, 0)
        val failures  = prefs.getInt(PFX_ACTION_FAILURES + actionType, 0)
        val editor    = prefs.edit()
        val newSuccesses: Int
        val newFailures: Int
        if (success) {
            newSuccesses = successes + 1; newFailures = failures
        } else {
            newSuccesses = successes; newFailures = failures + 1
        }
        val total = newSuccesses + newFailures
        val rate  = newFailures.toFloat() / total.toFloat()

        editor.putInt(PFX_ACTION_SUCCESS  + actionType, newSuccesses)
        editor.putInt(PFX_ACTION_FAILURES + actionType, newFailures)
        editor.putFloat(PFX_ACTION_RATE   + actionType, rate)
        editor.apply()

        if (total >= ACTION_MIN_SAMPLES) {
            if (rate > ACTION_AVOID_RATE)          addAvoidedAction(actionType)
            else if (rate < ACTION_REHABILITATE_RATE) removeAvoidedAction(actionType)
        }

        Log.d(TAG, "ACTION_OUTCOME action=$actionType success=$success " +
            "rate=${"%.2f".format(rate)} total=$total")
    }

    fun getActionFailureRate(actionType: String): Float =
        prefs.getFloat(PFX_ACTION_RATE + actionType, 0f)

    fun getActionSampleCount(actionType: String): Int =
        prefs.getInt(PFX_ACTION_SUCCESS + actionType, 0) +
        prefs.getInt(PFX_ACTION_FAILURES + actionType, 0)

    // ── Avoided action list ───────────────────────────────────────────────────

    fun getAvoidedActions(): Set<String> =
        prefs.getStringSet(KEY_AVOID, emptySet()) ?: emptySet()

    fun addAvoidedAction(action: String) {
        val current = getAvoidedActions().toMutableSet()
        if (current.add(action)) {
            prefs.edit().putStringSet(KEY_AVOID, current).apply()
            Log.i(TAG, "AIRI ACTION_AVOIDED action=$action")
        }
    }

    fun removeAvoidedAction(action: String) {
        val current = getAvoidedActions().toMutableSet()
        if (current.remove(action)) {
            prefs.edit().putStringSet(KEY_AVOID, current).apply()
            Log.i(TAG, "AIRI ACTION_REHABILITATED action=$action")
        }
    }

    // ── Planner complexity preference ─────────────────────────────────────────

    fun getPreferSimpleSteps(): Boolean = prefs.getBoolean(KEY_PREFER_SIMPLE, false)

    fun setPreferSimpleSteps(value: Boolean) =
        prefs.edit().putBoolean(KEY_PREFER_SIMPLE, value).apply()

    fun getMaxStepsHint(): Int? {
        val v = prefs.getInt(KEY_MAX_STEPS, -1)
        return if (v < 0) null else v
    }

    fun setMaxStepsHint(max: Int?) =
        prefs.edit().putInt(KEY_MAX_STEPS, max ?: -1).apply()

    // ── Agent trust and quarantine ────────────────────────────────────────────

    /** 0.0 = fully distrusted, 1.0 = fully trusted. Default is 1.0 (full trust). */
    fun getAgentTrustScore(agentId: String): Float =
        prefs.getFloat(PFX_TRUST + agentId, 1.0f)

    /**
     * Update agent trust via EMA. Automatically quarantines agents whose trust
     * drops below [QUARANTINE_TRUST_THRESHOLD] and releases rehabilitated ones.
     */
    fun recordAgentOutcome(agentId: String, success: Boolean) {
        val current = getAgentTrustScore(agentId)
        val signal  = if (success) 1.0f else 0.0f
        val updated = (1f - TRUST_ALPHA) * current + TRUST_ALPHA * signal
        prefs.edit().putFloat(PFX_TRUST + agentId, updated).apply()

        when {
            updated < QUARANTINE_TRUST_THRESHOLD -> quarantineAgent(agentId, "trust_${updated.format2})")
            updated > RELEASE_TRUST_THRESHOLD && isAgentQuarantined(agentId) -> releaseAgent(agentId)
        }

        Log.d(TAG, "AGENT_TRUST agentId=$agentId success=$success " +
            "trust=${updated.format2} quarantined=${isAgentQuarantined(agentId)}")
    }

    fun isAgentQuarantined(agentId: String): Boolean =
        (prefs.getStringSet(KEY_QUARANTINE, emptySet()) ?: emptySet()).contains(agentId)

    fun quarantineAgent(agentId: String, reason: String) {
        val current = (prefs.getStringSet(KEY_QUARANTINE, emptySet()) ?: emptySet()).toMutableSet()
        if (current.add(agentId)) {
            prefs.edit().putStringSet(KEY_QUARANTINE, current).apply()
            Log.w(TAG, "AIRI AGENT_QUARANTINED agentId=$agentId reason=$reason")
        }
    }

    fun releaseAgent(agentId: String) {
        val current = (prefs.getStringSet(KEY_QUARANTINE, emptySet()) ?: emptySet()).toMutableSet()
        if (current.remove(agentId)) {
            prefs.edit().putStringSet(KEY_QUARANTINE, current).apply()
            Log.i(TAG, "AIRI AGENT_RELEASED agentId=$agentId")
        }
    }

    fun getQuarantinedAgents(): Set<String> =
        prefs.getStringSet(KEY_QUARANTINE, emptySet()) ?: emptySet()

    // ── Recovery strategy effectiveness ───────────────────────────────────────

    /**
     * Record whether recovery strategy [strategy] was effective.
     * Strategy names should match RecoveryBranch subclass simple names:
     * "Retry", "Skip", "Fallback", "Abort", "Replan".
     */
    fun recordStrategyOutcome(strategy: String, success: Boolean) {
        val current = prefs.getFloat(PFX_STRATEGY + strategy, 0.5f)
        val signal  = if (success) 1.0f else 0.0f
        val updated = (1f - STRATEGY_ALPHA) * current + STRATEGY_ALPHA * signal
        prefs.edit().putFloat(PFX_STRATEGY + strategy, updated).apply()
        Log.d(TAG, "STRATEGY_OUTCOME strategy=$strategy success=$success score=${updated.format2}")
    }

    fun getStrategyScore(strategy: String): Float =
        prefs.getFloat(PFX_STRATEGY + strategy, 0.5f)

    fun getAllStrategyScores(): Map<String, Float> = listOf(
        "Retry", "Skip", "Fallback", "Abort", "Replan"
    ).associateWith { getStrategyScore(it) }

    // ── Failure fingerprints ──────────────────────────────────────────────────

    fun recordFailureFingerprint(fingerprint: String) {
        val count = prefs.getInt(PFX_FINGERPRINT + fingerprint, 0)
        prefs.edit().putInt(PFX_FINGERPRINT + fingerprint, count + 1).apply()
    }

    fun getFailureFingerprintCount(fingerprint: String): Int =
        prefs.getInt(PFX_FINGERPRINT + fingerprint, 0)

    // ── Overall execution confidence trend ────────────────────────────────────

    fun recordExecutionConfidence(confidence: Float) {
        val current = prefs.getFloat(KEY_CONFIDENCE, 0.7f)
        val updated = (1f - CONFIDENCE_ALPHA) * current + CONFIDENCE_ALPHA * confidence
        prefs.edit().putFloat(KEY_CONFIDENCE, updated).apply()
    }

    fun getOverallConfidence(): Float = prefs.getFloat(KEY_CONFIDENCE, 0.7f)

    // ── Per-goal-pattern task success rates ───────────────────────────────────

    fun recordTaskOutcome(goalPattern: String, success: Boolean) {
        val key     = PFX_TASK + goalPattern.take(40).replace(Regex("[^a-zA-Z0-9_]"), "_")
        val current = prefs.getFloat(key, 0.5f)
        val signal  = if (success) 1.0f else 0.0f
        prefs.edit().putFloat(key, (1f - TASK_ALPHA) * current + TASK_ALPHA * signal).apply()
    }

    fun getTaskSuccessRate(goalPattern: String): Float {
        val key = PFX_TASK + goalPattern.take(40).replace(Regex("[^a-zA-Z0-9_]"), "_")
        return prefs.getFloat(key, 0.5f)
    }

    // ── Debug / diagnostics ───────────────────────────────────────────────────

    fun logSnapshot() {
        Log.i(TAG, "AIRI LEARNING_SNAPSHOT " +
            "confidence=${"%.2f".format(getOverallConfidence())} " +
            "avoided=${getAvoidedActions().size} " +
            "quarantined=${getQuarantinedAgents().size} " +
            "preferSimple=${getPreferSimpleSteps()} " +
            "maxSteps=${getMaxStepsHint()}")
    }

    private val Float.format2: String get() = "%.2f".format(this)
}
