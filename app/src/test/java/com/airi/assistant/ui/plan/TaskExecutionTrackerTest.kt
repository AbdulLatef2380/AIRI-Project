package com.airi.assistant.ui.plan

import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionTrackerTest {

    @Test
    fun noPlanOrOrdinaryMessageDoesNotShowThePanel() {
        val tracker = TaskExecutionTracker()

        tracker.onExecutionState(AgentState(executionStage = ExecutionStage.IDLE))
        tracker.onExecutionState(
            AgentState(
                executionStage = ExecutionStage.PLANNING,
                executionId = "",
                activeGoalDescription = "",
                nodesTotal = 0,
            )
        )

        assertTrue(tracker.steps.value.isEmpty())
        assertFalse(tracker.isVisible.value)
    }

    @Test
    fun admittedPlanShowsOnlyRealActionsAndCompletesThem() {
        val tracker = TaskExecutionTracker()
        val executionId = "execution-a"

        tracker.onExecutionState(planState(executionId, ExecutionStage.PLANNING))
        tracker.onExecutionState(
            planState(
                executionId,
                ExecutionStage.EXECUTING,
                activeNodeId = "tool_1_search",
                activeNodeAction = "Search local knowledge",
            )
        )
        tracker.onExecutionState(planState(executionId, ExecutionStage.COMPLETED))

        val steps = tracker.steps.value
        assertEquals(listOf("Inspect the project", "Search local knowledge"), steps.map { it.label })
        assertTrue(steps.none { it.label.matches(Regex("Step \\d+")) })
        assertTrue(steps.all { it.status == PlanStepStatus.COMPLETED })
        assertTrue(tracker.isVisible.value)
    }

    @Test
    fun failureAndCancellationRemainDistinctTerminalOutcomes() {
        val failed = TaskExecutionTracker()
        failed.onExecutionState(planState("failed", ExecutionStage.PLANNING))
        failed.onExecutionState(planState("failed", ExecutionStage.EXECUTING, "node", "Read file"))
        failed.onExecutionState(planState("failed", ExecutionStage.FAILED))
        assertTrue(failed.steps.value.all { it.status == PlanStepStatus.FAILED })

        val cancelled = TaskExecutionTracker()
        cancelled.onExecutionState(planState("cancelled", ExecutionStage.PLANNING))
        cancelled.onExecutionState(planState("cancelled", ExecutionStage.EXECUTING, "node", "Read file"))
        cancelled.onExecutionState(planState("cancelled", ExecutionStage.CANCELLED))
        assertTrue(cancelled.steps.value.all { it.status == PlanStepStatus.CANCELLED })
    }

    @Test
    fun staleExecutionEventCannotModifyTheNewPlan() {
        val tracker = TaskExecutionTracker()
        tracker.onExecutionState(planState("old", ExecutionStage.PLANNING, goal = "Old task"))
        tracker.onExecutionState(planState("new", ExecutionStage.PLANNING, goal = "New task"))
        tracker.onExecutionState(
            planState("old", ExecutionStage.EXECUTING, "old_node", "Old action", goal = "Old task")
        )

        assertEquals(listOf("New task"), tracker.steps.value.map { it.label })
    }

    private fun planState(
        executionId: String,
        stage: ExecutionStage,
        activeNodeId: String = "",
        activeNodeAction: String = "",
        goal: String = "Inspect the project",
    ) = AgentState(
        isWorking = stage !in setOf(ExecutionStage.COMPLETED, ExecutionStage.FAILED, ExecutionStage.CANCELLED),
        executionStage = stage,
        executionId = executionId,
        activeGoalDescription = goal,
        activeNodeId = activeNodeId,
        activeNodeAction = activeNodeAction,
    )
}
