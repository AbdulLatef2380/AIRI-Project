package com.airi.assistant.agent.planning

import android.util.Log
import java.util.UUID

/**
 * ActionPlan → TypedPlanGraph conversion extensions.
 *
 * [ActionPlan] is produced by [PlanGenerator.createDAGPlanFromLLM].
 * [TypedPlanGraph] is consumed by [UnifiedCognitiveLoop.executeGraph].
 *
 * These extensions bridge the two representations so [ChatViewModel] can pass
 * an LLM-generated plan into the parallel-wave DAG executor without additional
 * parsing or restructuring.
 *
 * ## Conversion strategy
 * Each [PlanStep] becomes a [GoalNode] whose `action` and `params` fields are
 * derived from the step type. The `dependsOn` list is preserved verbatim.
 * Critical-step status is determined by step type — Wait steps are non-critical
 * (their failure does not abort the graph); all others are critical by default.
 *
 * ## Recovery budget
 * Nodes default to `RecoveryBranch.Retry(maxAttempts = 2)` which matches the
 * TypedPlanGraph default. Callers can override per-node if needed.
 *
 * ## Phase note
 * Created in Phase 2 pre-migration to satisfy the pre-migration checklist.
 * Previously this conversion was missing, making [UCL.executeGraph] unreachable
 * from any caller even if wired.
 */

private const val TAG = "ActionPlanConvert"

/**
 * Convert this [ActionPlan] to a [TypedPlanGraph] suitable for
 * [UnifiedCognitiveLoop.executeGraph].
 *
 * @param goalId Unique ID for this execution run. Defaults to a UUID.
 *               Should match the chat session ID so workspace snapshots
 *               can be linked to a specific conversation.
 */
fun ActionPlan.toTypedPlanGraph(goalId: String = UUID.randomUUID().toString()): TypedPlanGraph {
    val graph = TypedPlanGraph(
        goalId      = goalId,
        description = intent.take(120)
    )

    if (steps.isEmpty()) {
        Log.w(TAG, "TYPED_PLAN_GRAPH_EMPTY intentChars=${intent.length}")
        // Create a single no-op node so the graph is not empty.
        // PlanQualityScorer will give this a low confidence score and likely
        // reject it before execution, which is the correct behavior for an
        // empty plan.
        graph.addNode(
            GoalNode(
                id          = "noop_${goalId.take(8)}",
                description = "No-op: plan had no steps",
                action      = "noop",
                params      = emptyMap(),
                dependsOn   = emptyList(),
                isCritical  = false
            )
        )
        return graph
    }

    steps.forEach { step ->
        graph.addNode(step.toGoalNode())
    }

    Log.d(TAG, "TYPED_PLAN_GRAPH_CREATED goalId=$goalId nodeCount=${steps.size} intentChars=${intent.length}")
    return graph
}

/**
 * Convert a [PlanStep] to a [GoalNode].
 *
 * The `action` field uses a canonical verb-noun format that [SubAgentRegistry.route]
 * keyword-scores against. The `params` map carries structured step data so
 * [UCL.runNode] can reconstruct a meaningful routing string.
 */
private fun PlanStep.toGoalNode(): GoalNode = when (this) {
    is PlanStep.OpenApp -> GoalNode(
        id          = id,
        description = "Open app: $appName",
        action      = "open_app",
        params      = buildMap {
            put("app_name", appName)
            packageName?.let { put("package", it) }
        },
        dependsOn   = dependsOn,
        isCritical  = true,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Search -> GoalNode(
        id          = id,
        description = "Search: $query",
        action      = "search",
        params      = mapOf("query" to query),
        dependsOn   = dependsOn,
        isCritical  = true,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Click -> GoalNode(
        id          = id,
        description = "Click: $targetText",
        action      = "click",
        params      = buildMap {
            put("target_text", targetText)
            targetId?.let { put("target_id", it) }
        },
        dependsOn   = dependsOn,
        isCritical  = true,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Type -> GoalNode(
        id          = id,
        description = "Type text${targetField?.let { " in $it" } ?: ""}",
        action      = "type",
        params      = buildMap {
            put("text", text)
            targetField?.let { put("target_field", it) }
        },
        dependsOn   = dependsOn,
        isCritical  = true,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Navigate -> GoalNode(
        id          = id,
        description = "Navigate ${direction.name.lowercase()}",
        action      = "navigate",
        params      = mapOf("direction" to direction.name.lowercase()),
        dependsOn   = dependsOn,
        isCritical  = false,   // navigation failure usually doesn't abort a plan
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Wait -> GoalNode(
        id          = id,
        description = "Wait${condition?.let { " until: $it" } ?: " ${durationMs}ms"}",
        action      = "wait",
        params      = buildMap {
            durationMs?.let { put("duration_ms", it.toString()) }
            condition?.let  { put("condition", it) }
        },
        dependsOn   = dependsOn,
        recoveryBranch  = RecoveryBranch.Skip,   // waits are always skippable
        isCritical  = false,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Scroll -> GoalNode(
        id          = id,
        description = "Scroll ${direction.name.lowercase()} ${amount}x",
        action      = "scroll",
        params      = mapOf(
            "direction" to direction.name.lowercase(),
            "amount"    to amount.toString()
        ),
        dependsOn   = dependsOn,
        isCritical  = false,
        expectedOutcome = expectedOutcome
    )

    is PlanStep.Custom -> GoalNode(
        id          = id,
        description = "Custom: $action",
        action      = action,
        params      = parameters,
        dependsOn   = dependsOn,
        isCritical  = true,
        expectedOutcome = expectedOutcome
    )
}
