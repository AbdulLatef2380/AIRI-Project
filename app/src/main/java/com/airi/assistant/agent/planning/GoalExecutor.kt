package com.airi.assistant.agent.planning

import android.util.Log
import com.airi.assistant.agent.execution.runtime.ExecutionGraphEvent
import com.airi.assistant.agent.execution.runtime.ExecutionGraphRuntime
import com.airi.assistant.agent.execution.runtime.PlanExecutionState
import com.airi.assistant.agent.subagent.SubAgentContext

open class GoalExecutor(
    private val graphRuntime: ExecutionGraphRuntime? = null
) {

    companion object {
        private const val TAG = "GoalExecutor"
    }

    /**
     * Execute [goal] through the DAG runtime.
     *
     * Returns `true` when every node in the plan completed successfully.
     * Falls back to a trivial "has steps" check when no runtime is injected
     * (e.g. in unit-test scaffolding).
     */
    open suspend fun executeGoal(goal: AgentGoal): Boolean {
        val runtime = graphRuntime
            ?: return goal.steps.isNotEmpty().also {
                Log.w(TAG, "No ExecutionGraphRuntime injected; returning trivial result")
            }

        val plan = ActionPlan(
            intent = goal.description,
            confidence = 0.9,
            steps = goal.steps,
            requiresConfirmation = false
        )
        val context = SubAgentContext.test(
            sessionId = "goal-${goal.id}",
            userId = "system"
        )

        var finalState = PlanExecutionState.CREATED

        val graphResult = runtime.execute(plan, context) { event ->
            when (event) {
                is ExecutionGraphEvent.PlanStarted ->
                    Log.i(TAG, "AIRI_PROOF GOAL_PLAN_STARTED intent=${event.intent} nodes=${event.totalNodes}")
                is ExecutionGraphEvent.WaveStarted ->
                    Log.i(TAG, "AIRI_PROOF GOAL_WAVE nodes=${event.nodeIds}")
                is ExecutionGraphEvent.NodeStarted ->
                    Log.d(TAG, "Node started: ${event.nodeId} agent=${event.agentId}")
                is ExecutionGraphEvent.NodeCompleted ->
                    Log.i(TAG, "AIRI_PROOF GOAL_NODE_COMPLETED id=${event.nodeId}")
                is ExecutionGraphEvent.NodeFailed ->
                    Log.w(TAG, "AIRI_PROOF GOAL_NODE_FAILED id=${event.nodeId} reason=${event.reason}")
                is ExecutionGraphEvent.GraphSnapshot ->
                    finalState = event.snapshot.executionState
                is ExecutionGraphEvent.PlanCompleted ->
                    finalState = event.snapshot.executionState
                is ExecutionGraphEvent.Reflection ->
                    Log.w(TAG, "Reflection: ${event.message}")
                else -> Unit
            }
        }

        val succeeded = graphResult.snapshot.executionState == PlanExecutionState.COMPLETED
        if (succeeded) {
            Log.i(TAG, "AIRI_PROOF GOAL_COMPLETED id=${goal.id}")
        } else {
            Log.w(TAG, "AIRI_PROOF GOAL_INCOMPLETE id=${goal.id} state=$finalState " +
                    "failed=${graphResult.snapshot.failedNodeIds}")
        }
        return succeeded
    }
}
