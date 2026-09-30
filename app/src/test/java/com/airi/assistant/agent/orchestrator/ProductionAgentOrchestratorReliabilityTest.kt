package com.airi.assistant.agent.orchestrator

import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ProductionAgentOrchestratorReliabilityTest {
    @Test
    fun finalResultUsesDeclaredSinkAndParallelEventsAreRetained() = runBlocking {
        val agent = TestAgent { input ->
            delay(if (input == "fast") 5L else 80L)
            emit(AgentEvent.Progress("finished $input", 100))
            emit(AgentEvent.Complete(input, durationMs = 80L))
        }
        val orchestrator = orchestratorFor(agent)

        try {
            val result = orchestrator.executePlan(
                plan(
                    id = "final-sink-plan",
                    tasks = listOf(
                        task("fast-task", "fast"),
                        task("slow-task", "slow")
                    ),
                    finalTaskId = "slow-task"
                )
            )

            assertTrue(result is ProductionAgentOrchestrator.ExecutionResult.Success)
            val success = result as ProductionAgentOrchestrator.ExecutionResult.Success
            assertEquals("slow", success.finalResult)
            assertEquals(4, success.eventsEmitted.size)
        } finally {
            orchestrator.cancelAll()
        }
    }

    @Test
    fun cancellingOnePlanDoesNotCancelItsSiblingPlan() = runBlocking {
        val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        started["cancel"] = CompletableDeferred()
        started["survive"] = CompletableDeferred()
        val agent = TestAgent { input ->
            started.getValue(input).complete(Unit)
            if (input == "cancel") awaitCancellation()
            delay(40L)
            emit(AgentEvent.Complete("completed:$input", durationMs = 40L))
        }
        val orchestrator = orchestratorFor(agent)

        try {
            val cancelledPlan = async(Dispatchers.Default) {
                orchestrator.executePlan(plan(id = "cancel-plan", tasks = listOf(task("cancel-task", "cancel"))))
            }
            val survivingPlan = async(Dispatchers.Default) {
                orchestrator.executePlan(plan(id = "survive-plan", tasks = listOf(task("survive-task", "survive"))))
            }

            withTimeout(2_000L) { started.values.map { async { it.await() } }.awaitAll() }
            assertTrue(orchestrator.cancel("cancel-plan"))

            val cancelled = withTimeout(2_000L) { cancelledPlan.await() }
            val survived = withTimeout(2_000L) { survivingPlan.await() }
            assertTrue(cancelled is ProductionAgentOrchestrator.ExecutionResult.PartialFailure)
            assertTrue(survived is ProductionAgentOrchestrator.ExecutionResult.Success)
            assertEquals("completed:survive", (survived as ProductionAgentOrchestrator.ExecutionResult.Success).finalResult)
        } finally {
            orchestrator.cancelAll()
        }
    }

    @Test
    fun agentFlowWithoutTerminalCompleteCannotReportSuccess() = runBlocking {
        val agent = TestAgent { _ -> emit(AgentEvent.PartialResult("unfinished draft")) }
        val orchestrator = orchestratorFor(agent)

        try {
            val result = orchestrator.executePlan(
                plan(id = "missing-terminal-plan", tasks = listOf(task("missing-terminal-task", "missing-terminal")))
            )

            assertTrue(result is ProductionAgentOrchestrator.ExecutionResult.PartialFailure)
            val failure = result as ProductionAgentOrchestrator.ExecutionResult.PartialFailure
            assertTrue(failure.taskErrors.getValue("missing-terminal-task").contains("terminal Complete"))
        } finally {
            orchestrator.cancelAll()
        }
    }

    private fun orchestratorFor(agent: TestAgent) = ProductionAgentOrchestrator(
        agentCapabilities = { listOf(agent.capability) },
        routeAgent = { _, _ -> agent },
        findAgent = { id -> agent.takeIf { it.capability.agentId == id } }
    )

    private fun plan(
        id: String,
        tasks: List<ProductionAgentOrchestrator.OrchestratorTask>,
        finalTaskId: String? = null
    ) = ProductionAgentOrchestrator.OrchestratorPlan(
        id = id,
        tasks = tasks,
        finalTaskId = finalTaskId
    )

    private fun task(id: String, input: String) = ProductionAgentOrchestrator.OrchestratorTask(
        id = id,
        description = input,
        agentId = "test-agent",
        dependencies = emptyList(),
        input = input,
        context = SubAgentContext.test()
    )

    private class TestAgent(
        private val behavior: suspend FlowCollector<AgentEvent>.(String) -> Unit
    ) : SubAgent {
        override val capability = SubAgentCapability(
            agentId = "test-agent",
            displayName = "Test agent",
            description = "Deterministic test agent",
            intentKeywords = listOf("test"),
            requiresCloud = false,
            costTier = SubAgentCapability.CostTier.FREE
        )

        override suspend fun canHandle(input: String, context: SubAgentContext) = true

        override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
            behavior(input)
        }
    }
}
