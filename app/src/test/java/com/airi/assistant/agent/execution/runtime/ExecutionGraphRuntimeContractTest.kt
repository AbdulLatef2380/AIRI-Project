package com.airi.assistant.agent.execution.runtime

import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.core.planning.ActionPlan
import com.airi.core.planning.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExecutionGraphRuntimeContractTest {
    @Test
    fun equivalentPlansReuseTheSameRuntimePlanId() {
        val runtime = ExecutionGraphRuntime(ProductionAgentOrchestrator())
        val plan = ActionPlan(
            intent = "open settings",
            confidence = 0.9,
            steps = listOf(PlanStep.OpenApp(id = "open", appName = "Settings")),
        )
        val first = runtimePlanId(runtime, plan)
        val second = runtimePlanId(runtime, plan.copy())

        assertEquals(first, second)
        assertNotEquals("a fresh UUID must not identify every resume", first, runtimePlanId(runtime, plan.copy(intent = "open browser")))
    }

    @Test
    fun cancelPublishesTerminalCancelledSnapshot() {
        val runtime = ExecutionGraphRuntime(ProductionAgentOrchestrator())
        runtime.restore(
            ExecutionGraphSnapshot(
                planId = "stable-plan",
                planIntent = "test",
                nodes = emptyList(),
                executionState = PlanExecutionState.RUNNING,
            )
        )

        runtime.cancel("stable-plan")

        assertEquals(PlanExecutionState.CANCELLED, runtime.currentSnapshot("stable-plan")?.executionState)
    }

    private fun runtimePlanId(runtime: ExecutionGraphRuntime, plan: ActionPlan): String {
        val method = ExecutionGraphRuntime::class.java.getDeclaredMethod("buildRuntimePlan", ActionPlan::class.java)
            .apply { isAccessible = true }
        val runtimePlan = method.invoke(runtime, plan)
        return runtimePlan.javaClass.getDeclaredField("planId")
            .apply { isAccessible = true }
            .get(runtimePlan) as String
    }
}
