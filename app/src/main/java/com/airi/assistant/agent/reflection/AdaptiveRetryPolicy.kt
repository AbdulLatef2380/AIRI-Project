package com.airi.assistant.agent.reflection

import android.util.Log
import com.airi.assistant.agent.planning.RecoveryStrategy
import java.util.concurrent.ConcurrentHashMap

/**
 * AdaptiveRetryPolicy — failure-pattern-aware retry strategy selector.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Replaces blind exponential backoff with an adaptive policy that:
 *
 *   1. Tracks per-action-type failure counts across the session.
 *   2. When a failure occurs, checks if this action type has a high failure
 *      rate — if so, escalates immediately to COMPENSATE or ABORT instead
 *      of wasting retries on a known-bad action.
 *   3. Classifies failure messages into error categories and selects the most
 *      appropriate [RecoveryStrategy] for each category.
 *   4. Provides per-category backoff delays (network errors get longer backoff
 *      than logic errors).
 *
 * ── INTEGRATION ───────────────────────────────────────────────────────────────
 * Called by [ExecutionReflector] to generate [ReflectionReport.recommendations]
 * and can be used directly by orchestration code before submitting a retry.
 *
 * ── THREAD SAFETY ─────────────────────────────────────────────────────────────
 * Uses [ConcurrentHashMap] for failure counts — safe for parallel wave reads.
 * Counts are read-then-updated atomically via compute().
 */
class AdaptiveRetryPolicy {

    companion object {
        private const val TAG = "AdaptiveRetryPolicy"

        /** Action types with ≥ this failure rate are treated as systematically broken. */
        private const val SYSTEMATIC_FAILURE_THRESHOLD = 0.65f

        /** Minimum sample size before systematic judgement. */
        private const val MIN_SAMPLES_FOR_JUDGEMENT = 3

        /** Base backoff per error category (ms). */
        private val BACKOFF_MS = mapOf(
            ErrorCategory.NETWORK    to 2000L,
            ErrorCategory.TIMEOUT    to 3000L,
            ErrorCategory.PERMISSION to 500L,
            ErrorCategory.LOGIC      to 300L,
            ErrorCategory.RESOURCE   to 1000L,
            ErrorCategory.UNKNOWN    to 500L
        )
    }

    // ── Failure tracking ──────────────────────────────────────────────────────

    private val successCounts = ConcurrentHashMap<String, Int>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Record a success for [actionType]. */
    fun recordSuccess(actionType: String) {
        successCounts.compute(actionType) { _, v -> (v ?: 0) + 1 }
    }

    /** Record a failure for [actionType]. */
    fun recordFailure(actionType: String) {
        failureCounts.compute(actionType) { _, v -> (v ?: 0) + 1 }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Select the optimal [RecoveryStrategy] for a failure.
     *
     * @param actionType     The action type that failed (e.g., "open_app", "search").
     * @param failureMessage The raw error message from the failed node.
     * @param attemptNumber  Current attempt number (1-indexed).
     * @return               [RetryDecision] with strategy + backoff.
     */
    fun selectStrategy(
        actionType:     String,
        failureMessage: String,
        attemptNumber:  Int
    ): RetryDecision {
        // ── Check for systematic failure ──────────────────────────────────────
        val failures  = failureCounts[actionType] ?: 0
        val successes = successCounts[actionType] ?: 0
        val total     = failures + successes
        if (total >= MIN_SAMPLES_FOR_JUDGEMENT) {
            val failureRate = failures.toFloat() / total
            if (failureRate >= SYSTEMATIC_FAILURE_THRESHOLD) {
                Log.w(TAG, "SYSTEMATIC_FAILURE action=$actionType rate=${"%.2f".format(failureRate)} → COMPENSATE")
                return RetryDecision(
                    strategy  = RecoveryStrategy.COMPENSATE,
                    backoffMs = 0L,
                    reason    = "Action '$actionType' has ${(failureRate * 100).toInt()}% failure rate — switching to compensation."
                )
            }
        }

        // ── Error category classification ─────────────────────────────────────
        val category = classifyError(failureMessage)
        val strategy = when (category) {
            ErrorCategory.NETWORK    -> if (attemptNumber < 3) RecoveryStrategy.REPLAN else RecoveryStrategy.COMPENSATE
            ErrorCategory.TIMEOUT    -> RecoveryStrategy.COMPENSATE
            ErrorCategory.PERMISSION -> RecoveryStrategy.ABORT
            ErrorCategory.LOGIC      -> if (attemptNumber < 2) RecoveryStrategy.REDUCE_SCOPE else RecoveryStrategy.COMPENSATE
            ErrorCategory.RESOURCE   -> if (attemptNumber < 2) RecoveryStrategy.REPLAN else RecoveryStrategy.COMPENSATE
            ErrorCategory.UNKNOWN    -> if (attemptNumber < 2) RecoveryStrategy.REPLAN else RecoveryStrategy.ABORT
        }

        // Exponential backoff capped at 8x base
        val baseMs    = BACKOFF_MS[category] ?: 500L
        val backoffMs = baseMs * (1L shl minOf(attemptNumber - 1, 3))

        Log.d(TAG, "ADAPTIVE_RETRY action=$actionType category=$category attempt=$attemptNumber " +
            "→ strategy=$strategy backoff=${backoffMs}ms")

        return RetryDecision(strategy = strategy, backoffMs = backoffMs, reason = "category=$category")
    }

    /**
     * Returns true if this action type is known-bad and should NOT be retried.
     * Used for fast-path rejection before even attempting a retry.
     */
    fun isSystematicallyFailing(actionType: String): Boolean {
        val failures  = failureCounts[actionType] ?: 0
        val successes = successCounts[actionType] ?: 0
        val total     = failures + successes
        if (total < MIN_SAMPLES_FOR_JUDGEMENT) return false
        return (failures.toFloat() / total) >= SYSTEMATIC_FAILURE_THRESHOLD
    }

    /** All action types currently flagged as systematically failing. */
    fun systematicallyFailingActions(): List<String> =
        (failureCounts.keys + successCounts.keys).distinct().filter { isSystematicallyFailing(it) }

    /** Reset all counters (e.g., on new session). */
    fun reset() {
        successCounts.clear()
        failureCounts.clear()
    }

    // ── Error classification ──────────────────────────────────────────────────

    private fun classifyError(message: String): ErrorCategory {
        val m = message.lowercase()
        return when {
            m.contains("network") || m.contains("connect") ||
            m.contains("socket")  || m.contains("unreachable")  -> ErrorCategory.NETWORK
            m.contains("timeout") || m.contains("timed out")    -> ErrorCategory.TIMEOUT
            m.contains("permission") || m.contains("denied") ||
            m.contains("security")                               -> ErrorCategory.PERMISSION
            m.contains("null") || m.contains("illegal") ||
            m.contains("class cast") || m.contains("index")     -> ErrorCategory.LOGIC
            m.contains("memory") || m.contains("space") ||
            m.contains("disk")  || m.contains("resource")       -> ErrorCategory.RESOURCE
            else                                                 -> ErrorCategory.UNKNOWN
        }
    }

    private enum class ErrorCategory {
        NETWORK, TIMEOUT, PERMISSION, LOGIC, RESOURCE, UNKNOWN
    }
}

data class RetryDecision(
    val strategy:  RecoveryStrategy,
    val backoffMs: Long,
    val reason:    String
)
