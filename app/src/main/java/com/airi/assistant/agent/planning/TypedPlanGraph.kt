package com.airi.assistant.agent.planning

import android.util.Log

// ─────────────────────────────────────────────────────────────────────────────
// TypedPlanGraph — DAG-based goal/subtask graph with recovery branches
//
// Replaces the flat List<PlanStep> model with a typed graph where:
//   • Every GoalNode carries a recovery strategy for each failure mode.
//   • Execution drives the graph via markDone / markFailed, which choose
//     the appropriate branch automatically.
//   • Self-correction: if a subtask fails with a recoverable error the
//     graph re-plans that branch before propagating failure upward.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "TypedPlanGraph"

// ── Node state machine ────────────────────────────────────────────────────────

enum class NodeStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED, RECOVERING }

// ── Recovery strategies ───────────────────────────────────────────────────────

sealed class RecoveryBranch {
    /** Retry the same step (up to [maxAttempts] times). */
    data class Retry(val maxAttempts: Int = 3) : RecoveryBranch()

    /** Skip this step and proceed with successors, marking the node SKIPPED. */
    object Skip : RecoveryBranch()

    /** Replace this node's action with [fallbackAction] and re-run once. */
    data class Fallback(val fallbackAction: String, val fallbackParams: Map<String, String> = emptyMap()) : RecoveryBranch()

    /** Abort the entire goal — propagate failure up the graph. */
    object Abort : RecoveryBranch()

    /** Trigger a full LLM-driven replan from the failed node onward. */
    object Replan : RecoveryBranch()
}

// ── Plan node ─────────────────────────────────────────────────────────────────

data class GoalNode(
    val id:              String,
    val description:     String,
    val action:          String,
    val params:          Map<String, String>    = emptyMap(),
    val dependsOn:       List<String>           = emptyList(),
    val recoveryBranch:  RecoveryBranch         = RecoveryBranch.Retry(maxAttempts = 2),
    val expectedOutcome: String?                = null,
    val isCritical:      Boolean                = true
) {
    var status:       NodeStatus = NodeStatus.PENDING
    var attempts:     Int        = 0
    var failReason:   String?    = null
    var output:       String?    = null
    var activeAction: String     = action
    var activeParams: Map<String, String> = params
}

// ── Typed plan graph ──────────────────────────────────────────────────────────

class TypedPlanGraph(
    val goalId:      String,
    val description: String,
    private val nodes: MutableMap<String, GoalNode> = mutableMapOf()
) {
    // All public mutation and read methods synchronize on `this` to prevent:
    //   • concurrent GoalNode var-field corruption across coroutines
    //   • TOCTOU races in readyNodes() → markRunning() sequences
    //   • ghost-PENDING reads after a concurrent markFailed → skipDownstream cascade

    // ── Graph construction ────────────────────────────────────────────────────

    @Synchronized
    fun addNode(node: GoalNode): TypedPlanGraph {
        nodes[node.id] = node
        return this
    }

    @Synchronized
    fun nodeCount(): Int = nodes.size

    @Synchronized
    fun allNodes(): List<GoalNode> = nodes.values.toList()

    // ── Dependency resolution ─────────────────────────────────────────────────

    /**
     * Returns nodes whose dependencies are all DONE, filtered to PENDING only.
     * Synchronized so that the PENDING + all-deps-DONE predicate is evaluated
     * atomically with respect to concurrent markDone / markFailed calls.
     */
    @Synchronized
    fun readyNodes(): List<GoalNode> {
        return nodes.values.filter { node ->
            node.status == NodeStatus.PENDING &&
            node.dependsOn.all { depId -> nodes[depId]?.status == NodeStatus.DONE }
        }
    }

    /** True when every node is either DONE or SKIPPED (graph finished). */
    @Synchronized
    fun isComplete(): Boolean =
        nodes.values.all { it.status == NodeStatus.DONE || it.status == NodeStatus.SKIPPED }

    /** True when any critical node is permanently FAILED (no recovery left). */
    @Synchronized
    fun isFailed(): Boolean =
        nodes.values.any { it.isCritical && it.status == NodeStatus.FAILED }

    // ── Execution hooks ───────────────────────────────────────────────────────

    @Synchronized
    fun markRunning(nodeId: String) {
        nodes[nodeId]?.let {
            it.status = NodeStatus.RUNNING
            it.attempts += 1
        }
    }

    /**
     * Mark a node as successfully done with optional output.
     * Propagates SKIPPED to any nodes that depended on this one as an abort guard.
     */
    @Synchronized
    fun markDone(nodeId: String, output: String? = null) {
        nodes[nodeId]?.let {
            it.status = NodeStatus.DONE
            it.output = output
            Log.i(TAG, "AIRI_PROOF GRAPH_NODE_DONE id=$nodeId output=${output?.take(60)}")
        }
    }

    /**
     * Mark a node as failed and apply its [RecoveryBranch].
     * Synchronized: skipDownstream recurses internally — JVM monitors are
     * reentrant so there is no deadlock risk.
     *
     * @return [RecoveryDecision] telling the executor what to do next.
     */
    @Synchronized
    fun markFailed(nodeId: String, reason: String): RecoveryDecision {
        val node = nodes[nodeId]
            ?: return RecoveryDecision.Abort("Unknown node: $nodeId")

        node.failReason = reason
        Log.w(TAG, "AIRI_PROOF GRAPH_NODE_FAILED id=$nodeId reason=$reason attempts=${node.attempts}")

        return when (val branch = node.recoveryBranch) {
            is RecoveryBranch.Retry -> {
                if (node.attempts < branch.maxAttempts) {
                    node.status = NodeStatus.RECOVERING
                    Log.i(TAG, "GRAPH_RECOVER_RETRY id=$nodeId attempt=${node.attempts}/${branch.maxAttempts}")
                    RecoveryDecision.Retry(node)
                } else {
                    node.status = NodeStatus.FAILED
                    skipDownstream(nodeId)
                    if (node.isCritical) RecoveryDecision.Abort(reason)
                    else RecoveryDecision.Skip(node)
                }
            }

            is RecoveryBranch.Fallback -> {
                if (node.attempts <= 1) {
                    node.status = NodeStatus.RECOVERING
                    node.activeAction = branch.fallbackAction
                    node.activeParams = branch.fallbackParams
                    Log.i(TAG, "GRAPH_RECOVER_FALLBACK id=$nodeId action=${branch.fallbackAction}")
                    RecoveryDecision.Retry(node)
                } else {
                    node.status = NodeStatus.FAILED
                    skipDownstream(nodeId)
                    if (node.isCritical) RecoveryDecision.Abort(reason) else RecoveryDecision.Skip(node)
                }
            }

            RecoveryBranch.Skip -> {
                node.status = NodeStatus.SKIPPED
                skipDownstream(nodeId)
                Log.i(TAG, "GRAPH_RECOVER_SKIP id=$nodeId")
                RecoveryDecision.Skip(node)
            }

            RecoveryBranch.Abort -> {
                node.status = NodeStatus.FAILED
                skipDownstream(nodeId)
                RecoveryDecision.Abort(reason)
            }

            RecoveryBranch.Replan -> {
                node.status = NodeStatus.RECOVERING
                Log.i(TAG, "GRAPH_RECOVER_REPLAN id=$nodeId")
                RecoveryDecision.RequestReplan(node, reason)
            }
        }
    }

    // ── Retry reset ────────────────────────────────────────────────────────────

    /**
     * Reset a RECOVERING node back to PENDING so the next execution wave
     * picks it up again, WITHOUT resetting its attempt counter.
     *
     * This is the correct implementation of the Retry recovery branch for
     * parallel wave scheduling. The previous sequential `continue` trick
     * inside a `for (node in ready)` loop left the node in RECOVERING state
     * and never actually retried it because [readyNodes] only returns PENDING
     * nodes — a silent bug.
     *
     * Called by [UnifiedCognitiveLoop.executeGraph] after receiving
     * [RecoveryDecision.Retry] in the wave result post-processing loop.
     */
    @Synchronized
    fun resetForRetry(nodeId: String) {
        nodes[nodeId]?.let { node ->
            if (node.status == NodeStatus.RECOVERING) {
                node.status = NodeStatus.PENDING
                Log.i(TAG, "AIRI_PROOF GRAPH_RETRY_RESET id=$nodeId attempts=${node.attempts}")
            }
        }
    }

    // ── Self-correction: patch a node in place with a new action/params ───────

    @Synchronized
    fun patchNode(nodeId: String, newAction: String, newParams: Map<String, String>) {
        nodes[nodeId]?.let {
            it.activeAction = newAction
            it.activeParams = newParams
            it.status = NodeStatus.PENDING
            it.attempts = 0
            Log.i(TAG, "AIRI_PROOF GRAPH_NODE_PATCHED id=$nodeId newAction=$newAction")
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @Synchronized
    fun snapshot(): GraphSnapshot = GraphSnapshot(
        goalId       = goalId,
        description  = description,
        totalNodes   = nodes.size,
        doneNodes    = nodes.values.count { it.status == NodeStatus.DONE },
        failedNodes  = nodes.values.count { it.status == NodeStatus.FAILED },
        skippedNodes = nodes.values.count { it.status == NodeStatus.SKIPPED },
        nodes        = nodes.values.map { it.copy() }
    )

    // ── Internal ──────────────────────────────────────────────────────────────

    // Called only from within @Synchronized methods — JVM reentrant monitor
    // guarantees this will not deadlock when invoked from markFailed.
    private fun skipDownstream(failedId: String) {
        nodes.values.forEach { n ->
            if (failedId in n.dependsOn && n.status == NodeStatus.PENDING) {
                n.status = NodeStatus.SKIPPED
                skipDownstream(n.id)
            }
        }
    }
}

// ── Recovery decision returned by markFailed ──────────────────────────────────

sealed class RecoveryDecision {
    data class Retry(val node: GoalNode) : RecoveryDecision()
    data class Skip(val node: GoalNode) : RecoveryDecision()
    data class Abort(val reason: String) : RecoveryDecision()
    data class RequestReplan(val failedNode: GoalNode, val reason: String) : RecoveryDecision()
}

// ── Graph snapshot (immutable view for observability) ─────────────────────────

data class GraphSnapshot(
    val goalId:       String,
    val description:  String,
    val totalNodes:   Int,
    val doneNodes:    Int,
    val failedNodes:  Int,
    val skippedNodes: Int,
    val nodes:        List<GoalNode>
) {
    val completionFraction: Float
        get() = if (totalNodes == 0) 1f
                else (doneNodes + skippedNodes).toFloat() / totalNodes.toFloat()
}

// ── Builder DSL ───────────────────────────────────────────────────────────────

fun buildPlanGraph(goalId: String, description: String, block: TypedPlanGraph.() -> Unit): TypedPlanGraph =
    TypedPlanGraph(goalId, description).apply(block)

fun TypedPlanGraph.node(
    id:             String,
    description:    String,
    action:         String,
    params:         Map<String, String>   = emptyMap(),
    dependsOn:      List<String>          = emptyList(),
    recovery:       RecoveryBranch        = RecoveryBranch.Retry(2),
    isCritical:     Boolean               = true,
    expectedOutcome: String?              = null
) = addNode(GoalNode(id, description, action, params, dependsOn, recovery, expectedOutcome, isCritical))
