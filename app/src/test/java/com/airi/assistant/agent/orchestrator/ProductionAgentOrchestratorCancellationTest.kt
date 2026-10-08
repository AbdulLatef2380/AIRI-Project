package com.airi.assistant.agent.orchestrator

import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAgentOrchestratorCancellationTest {

    @Test
    fun cancelAll_doesNotCancelTheCallingRequestScope() = runBlocking {
        val orchestrator = ProductionAgentOrchestrator()
        assertTrue(currentCoroutineContext().isActive)

        orchestrator.cancelAll()

        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun cancelAll_renewsRootScopeForLaterPlans() = runBlocking {
        val agent = object : SubAgent {
            override val capability = SubAgentCapability(
                agentId = "cancel-renew-agent",
                displayName = "Cancel renew test agent",
                description = "Deterministic cancellation test agent",
                intentKeywords = listOf("cancel-renew"),
                requiresCloud = false,
                costTier = SubAgentCapability.CostTier.FREE
            )

            override suspend fun canHandle(input: String, context: SubAgentContext) = true

            override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
                emit(AgentEvent.Complete("completed:$input", durationMs = 1L))
            }
        }
        val orchestrator = ProductionAgentOrchestrator(
            agentCapabilities = { listOf(agent.capability) },
            routeAgent = { _, _ -> agent },
            findAgent = { id, _ -> agent.takeIf { it.capability.agentId == id } }
        )

        orchestrator.cancelAll()
        val result = orchestrator.executePlan(
            ProductionAgentOrchestrator.OrchestratorPlan(
                id = "after-cancel-all",
                tasks = listOf(
                    ProductionAgentOrchestrator.OrchestratorTask(
                        id = "after-cancel-task",
                        description = "after cancelAll",
                        agentId = agent.capability.agentId,
                        dependencies = emptyList(),
                        input = "renewed",
                        context = SubAgentContext.test()
                    )
                )
            )
        )

        assertTrue(result is ProductionAgentOrchestrator.ExecutionResult.Success)
    }
}
