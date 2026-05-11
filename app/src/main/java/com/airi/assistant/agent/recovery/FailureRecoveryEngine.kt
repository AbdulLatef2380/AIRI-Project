package com.airi.assistant.agent.recovery

import android.util.Log
import com.airi.assistant.agent.adaptation.FailureIntelligenceEngine
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.NodeStatus
import com.airi.assistant.agent.planning.RecoveryBranch
import com.airi.assistant.agent.planning.TypedPlanGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * FailureRecoveryEngine — orchestrates multi-strategy failure recovery across
 * an agent execution graph.
 *
 * ── RECOVERY STRATEGIES (in priority order) ──────────────────────────────────
 *
 * 1. RETRY          — Re-run the exact same node action with backoff.
 * 2. FALLBACK       — Replace the node's action with a pre-specified fallback.
 * 3. SKIP           — Mark the node SKIPPED and proceed with its successors.
 * 4. REPLAN         — Request a full LLM replan from the failed node onward.
 * 5. ABORT          — Propagate terminal failure up the graph.
 *
 * The strategy is declared per-node in [GoalNode.recoveryBranch] and can
 * be overridden by [FailureIntelligenceEngine] pattern detection.
 *
 * ── SAFETY ───────────────────────────────────────────────────────────────────
 *
 *   - Maximum total recovery cycles per graph is capped at [MAX_GRAPH_RECOVERIES].
 *   - Exponential backoff up to [MAX_BACKOFF_MS] between retries.
 *   - All decisions are logged with AIRI_PROOF_RECOVERY tags.
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 *   AgentPlanner calls [attemptRecovery] when a node fails. The result
 *   determines whether execution continues, replans, or aborts.
 */
class FailureRecoveryEngine {

    private val TAG = "FailureRecoveryEngine"

    private val totalRecoveries = AtomicLong(0L)

    private val _recoveryLog = MutableStateFlow<List<RecoveryEvent>>(emptyList())
    val recoveryLog: StateFlow<List<RecoveryEvent>> = _recoveryLog.asStateFlow()

    // ── Data classes ──────────────────────────────────────────────────────────

    sealed class RecoveryOutcome {
        /** Retry the node after [delayMs] ms. */
        data class RetryAfter(val delayMs: Long, val attempt: Int) : RecoveryOutcome()
        /** Switch the node to [fallbackAction]. */
        data class UseFallback(val fallbackAction: String, val params: Map<String, String>) : RecoveryOutcome()
        /** Mark the node SKIPPED and continue. */
        object Skip : RecoveryOutcome()
        /** Request LLM-driven replan from this node forward. */
        object Replan : RecoveryOutcome()
        /** Abort the entire graph. */
        data class Abort(val reason: String) : RecoveryOutcome()
    }

    data class RecoveryEvent(
        val nodeId:      String,
        val nodeAction:  String,
        val strategy:    String,
        val outcome:     String,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    // ── Core recovery logic ───────────────────────────────────────────────────

    /**
     * Attempt recovery for a failed [node]. Returns a [RecoveryOutcome] that
     * the execution engine must act on.
     *
     * @param node         The failed GoalNode.
     * @param failReason   Human-readable failure message.
     * @param graphId      Parent graph ID for logging.
     */
    suspend fun attemptRecovery(
        node:       GoalNode,
        failReason: String,
        graphId:    String,
    ): RecoveryOutcome {
        val cycles = totalRecoveries.incrementAndGet()
        if (cycles > MAX_GRAPH_RECOVERIES) {
            return abort(node, "Global recovery cap exceeded ($MAX_GRAPH_RECOVERIES)", graphId)
        }

        val branch  = node.recoveryBranch
        val attempt = node.attempts

        Log.i(TAG, "AIRI_PROOF_RECOVERY graph=$graphId node=${node.id} branch=${branch::class.simpleName} attempt=$attempt reason='${failReason.take(80)}'")

        return when (branch) {
            is RecoveryBranch.Retry -> {
                if (attempt >= branch.maxAttempts) {
                    abort(node, "Max retries (${branch.maxAttempts}) exceeded", graphId)
                } else {
                    val backoff = calculateBackoff(attempt)
                    log(node, "retry", "delayMs=$backoff attempt=${attempt + 1}", graphId)
                    delay(backoff)
                    RecoveryOutcome.RetryAfter(backoff, attempt + 1)
                }
            }
            is RecoveryBranch.Fallback -> {
                if (attempt > 0) {
                    abort(node, "Fallback already tried once", graphId)
                } else {
                    log(node, "fallback", "action=${branch.fallbackAction}", graphId)
                    RecoveryOutcome.UseFallback(branch.fallbackAction, branch.fallbackParams)
                }
            }
            is RecoveryBranch.Skip -> {
                log(node, "skip", "node is non-critical or skip declared", graphId)
                RecoveryOutcome.Skip
            }
            is RecoveryBranch.Replan -> {
                log(node, "replan", "triggering llm replan", graphId)
                RecoveryOutcome.Replan
            }
            is RecoveryBranch.Abort -> {
                abort(node, "Abort declared in recovery branch", graphId)
            }
        }
    }

    /**
     * Emergency abort — for nodes that are [GoalNode.isCritical] and have
     * no viable recovery path.
     */
    fun abort(node: GoalNode, reason: String, graphId: String): RecoveryOutcome.Abort {
        log(node, "abort", reason, graphId)
        Log.e(TAG, "AIRI_PROOF_RECOVERY_ABORT graph=$graphId node=${node.id} reason='${reason.take(100)}'")
        return RecoveryOutcome.Abort(reason)
    }

    // ── Backoff calculation ───────────────────────────────────────────────────

    private fun calculateBackoff(attempt: Int): Long {
        val base = BASE_BACKOFF_MS * (1L shl attempt.coerceAtMost(5))
        return base.coerceAtMost(MAX_BACKOFF_MS)
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    private fun log(node: GoalNode, strategy: String, outcome: String, graphId: String) {
        val event = RecoveryEvent(
            nodeId     = node.id,
            nodeAction = node.action,
            strategy   = strategy,
            outcome    = outcome,
        )
        val current = _recoveryLog.value
        _recoveryLog.value = (current + event).takeLast(MAX_LOG_ENTRIES)
    }

    companion object {
        private const val BASE_BACKOFF_MS      = 500L
        private const val MAX_BACKOFF_MS       = 16_000L
        private const val MAX_GRAPH_RECOVERIES = 30L
        private const val MAX_LOG_ENTRIES      = 100
    }
}
