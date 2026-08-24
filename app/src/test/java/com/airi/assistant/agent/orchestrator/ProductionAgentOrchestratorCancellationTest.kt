package com.airi.assistant.agent.orchestrator

import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAgentOrchestratorCancellationTest {

    @Test
    fun cyclicPlanReturnsPartialFailureInsteadOfSuccess() = runBlocking {
        val context = SubAgentContext.test().copy(projectId = "project-1")
        val result = ProductionAgentOrchestrator().executePlan(
            ProductionAgentOrchestrator.OrchestratorPlan(
                projectId = "project-1",
                tasks = listOf(
                    ProductionAgentOrchestrator.OrchestratorTask(
                        id = "first",
                        description = "first",
                        agentId = null,
                        dependencies = listOf("second"),
                        input = "first",
                        context = context
                    ),
                    ProductionAgentOrchestrator.OrchestratorTask(
                        id = "second",
                        description = "second",
                        agentId = null,
                        dependencies = listOf("first"),
                        input = "second",
                        context = context
                    )
                )
            )
        )

        assertTrue(result is ProductionAgentOrchestrator.ExecutionResult.PartialFailure)
        val failure = result as ProductionAgentOrchestrator.ExecutionResult.PartialFailure
        assertTrue(failure.taskErrors.getValue("team_policy").contains("dependency cycle"))
    }

    @Test
    fun cancelAll_replacesCancelledScopeForFuturePlans() {
        val orchestrator = ProductionAgentOrchestrator()
        val field = ProductionAgentOrchestrator::class.java.getDeclaredField("orchestrationScope")
            .apply { isAccessible = true }

        val before = field.get(orchestrator) as CoroutineScope
        assertTrue(before.isActive())

        orchestrator.cancelAll()

        val after = field.get(orchestrator) as CoroutineScope
        assertNotSame(before, after)
        assertFalse(before.isActive())
        assertTrue(after.isActive())
    }

    private fun CoroutineScope.isActive(): Boolean =
        coroutineContext[Job]?.isActive == true
}
