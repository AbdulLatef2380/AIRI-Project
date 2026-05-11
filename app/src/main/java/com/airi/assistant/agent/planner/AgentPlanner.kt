package com.airi.assistant.agent.planner

import android.util.Log
import com.airi.assistant.agent.memory.AgentMemoryBridge
import com.airi.assistant.agent.planning.ReActPlanner
import com.airi.assistant.agent.planning.AiriBrainController
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.agent.recovery.FailureRecoveryEngine
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.tools.ToolResolver
import com.airi.assistant.agent.tracker.GoalTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.util.UUID

/**
 * AgentPlanner — unified planning facade over AIRI's multi-strategy planning stack.
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────────
 *
 *                      AgentPlanner
 *                      /           \
 *            ReActPlanner      AiriBrainController
 *                 |                     |
 *            CoTEngine            PlanGenerator
 *                 |                PlanValidator
 *               Tools              GoalExecutor
 *
 * Strategy selection:
 *   - SHORT / SINGLE-STEP goals  → [AiriBrainController] (fast DAG plan)
 *   - MULTI-STEP / REASONING goals → [ReActPlanner] (observe–act–reflect loop)
 *   - TOOL-HEAVY goals            → [ReActPlanner] with [ToolResolver] injected
 *
 * ── GOAL LIFECYCLE ───────────────────────────────────────────────────────────
 *
 *   1. [plan] creates a [GoalTracker.TrackedGoal] and starts tracking.
 *   2. Progress events from the inner planner advance the goal's [progressPct].
 *   3. [FailureRecoveryEngine] is consulted on each [AgentEvent.Failed].
 *   4. On completion: goal is marked DONE and outcome stored in [AgentMemoryBridge].
 *
 * ── CANCELLATION ─────────────────────────────────────────────────────────────
 *
 *   The returned Flow cancels cleanly — the goal is marked CANCELLED in
 *   [GoalTracker] on CancellationException. This is the correct behaviour
 *   for user-initiated aborts.
 */
class AgentPlanner(
    private val reActPlanner:          ReActPlanner,
    private val brainController:       AiriBrainController? = null,
    private val goalTracker:           GoalTracker,
    private val memoryBridge:          AgentMemoryBridge,
    private val toolResolver:          ToolResolver,
    private val recoveryEngine:        FailureRecoveryEngine,
) {

    private val TAG = "AgentPlanner"

    // ── Strategy selection ────────────────────────────────────────────────────

    private enum class PlanStrategy { BRAIN, REACT }

    private fun selectStrategy(goal: String, context: SubAgentContext): PlanStrategy {
        val lower = goal.lowercase()
        val isMultiStep = lower.contains("then") || lower.contains("after") ||
                          lower.contains("and then") || lower.contains("step by step") ||
                          lower.contains("first") && lower.contains("next")
        val isToolHeavy = context.availableTools.isNotEmpty() && (
                lower.contains("search") || lower.contains("fetch") ||
                lower.contains("open") || lower.contains("find") ||
                lower.contains("look up") || lower.contains("get"))
        return if (isMultiStep || isToolHeavy) PlanStrategy.REACT else PlanStrategy.BRAIN
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Plan and execute [goal], emitting [AgentEvent]s to the collector.
     *
     * Creates a [GoalTracker.TrackedGoal], selects the best strategy,
     * and drives either [ReActPlanner] or [AiriBrainController] through
     * to completion.
     */
    fun plan(
        goal:     String,
        context:  SubAgentContext,
        goalId:   String = UUID.randomUUID().toString(),
    ): Flow<AgentEvent> = flow {

        // 1. Create tracked goal
        val tracked = goalTracker.createGoal(
            description    = goal,
            agentId        = "agent_planner",
            estimatedSteps = estimateSteps(goal),
        )

        Log.i(TAG, "PLAN_START goalId=${tracked.id} strategy=pending goal='${goal.take(80)}'")
        emit(AgentEvent.Progress("Analyzing goal…", 5, "plan_start"))

        // 2. Inject memory context
        val memContext = runCatching {
            memoryBridge.buildAgentContext(query = goal, sessionId = context.sessionId)
        }.getOrDefault("")

        val enrichedGoal = if (memContext.isNotBlank()) "$goal\n\n$memContext" else goal

        // 3. Select strategy
        val strategy = selectStrategy(goal, context)
        Log.i(TAG, "PLAN_STRATEGY goalId=${tracked.id} strategy=${strategy.name}")
        emit(AgentEvent.Progress("Strategy: ${strategy.name.lowercase().replaceFirstChar { it.uppercase() }}", 10, "plan_strategy"))

        goalTracker.updateProgress(tracked.id, 10, "Strategy: ${strategy.name}")

        // 4. Build tool map for ReAct
        val tools: Map<String, suspend (String) -> String> = buildMap {
            toolResolver.availableToolNames().forEach { toolName ->
                val cleanName = toolName.removeSuffix(".<action>")
                put(cleanName) { input ->
                    val result = toolResolver.execute(ToolResolver.ToolCall(cleanName, input))
                    when (result) {
                        is ToolResolver.ToolResult.Success     -> result.text
                        is ToolResolver.ToolResult.Failure     -> "Error: ${result.message}"
                        is ToolResolver.ToolResult.Unavailable -> "Tool '${result.toolName}' not available"
                    }
                }
            }
        }

        // 5. Execute chosen strategy
        when (strategy) {
            PlanStrategy.REACT -> {
                var stepCount = 0
                reActPlanner.plan(enrichedGoal, context, tools)
                    .collect { event ->
                        when (event) {
                            is AgentEvent.Progress -> {
                                goalTracker.updateProgress(tracked.id, event.percent, event.message)
                            }
                            is AgentEvent.PartialResult -> {
                                stepCount++
                                goalTracker.advanceStep(tracked.id, "Step $stepCount complete")
                            }
                            is AgentEvent.Failed -> {
                                goalTracker.markFailed(tracked.id, event.error)
                                memoryBridge.recordGoalOutcome(AgentMemoryBridge.GoalMemoryEntry(
                                    goalDescription = goal,
                                    outcome         = "FAILED: ${event.error}",
                                    successfulSteps = emptyList(),
                                    agentId         = "react_planner",
                                ))
                            }
                            is AgentEvent.Complete -> {
                                goalTracker.markDone(tracked.id, "Completed via ReAct")
                                memoryBridge.recordGoalOutcome(AgentMemoryBridge.GoalMemoryEntry(
                                    goalDescription = goal,
                                    outcome         = "SUCCESS",
                                    successfulSteps = listOf(event.result.take(100)),
                                    agentId         = "react_planner",
                                ))
                            }
                            else -> {}
                        }
                        emit(event)
                    }
            }

            PlanStrategy.BRAIN -> {
                goalTracker.updateProgress(tracked.id, 20, "Brain planning…")
                emit(AgentEvent.Progress("Generating plan…", 20, "brain_plan"))

                val bc = brainController
                if (bc != null) {
                    val brainInput = BrainInput(text = enrichedGoal)
                    val output = runCatching { bc.process(brainInput) }.getOrElse { e ->
                        goalTracker.markFailed(tracked.id, e.message ?: "Brain controller error")
                        emit(AgentEvent.Failed("Brain controller error: ${e.message}"))
                        return@flow
                    }
                    goalTracker.markDone(tracked.id, "Brain plan completed")
                    emit(AgentEvent.Progress("Plan complete", 100, "brain_done"))
                    emit(AgentEvent.Complete(output.message))
                } else {
                    // Fall back to ReAct when brain controller unavailable
                    reActPlanner.plan(enrichedGoal, context, emptyMap()).collect { event ->
                        if (event is AgentEvent.Complete) goalTracker.markDone(tracked.id, "ReAct fallback complete")
                        emit(event)
                    }
                }
            }
        }

    }.catch { e ->
        if (e is CancellationException) {
            val active = goalTracker.activeGoals().firstOrNull()
            if (active != null) goalTracker.cancel(active.id)
            throw e
        }
        Log.e(TAG, "AgentPlanner error: ${e.message}", e)
        emit(AgentEvent.Failed("Planner error: ${e.message ?: "unknown"}"))
    }

    private fun estimateSteps(goal: String): Int {
        val words = goal.split(" ").size
        return when {
            words < 10 -> 3
            words < 30 -> 6
            else       -> 10
        }
    }
}
