package com.airi.assistant.agent.orchestrator

import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTeamPolicyTest {

    @Test
    fun admitsIsolatedPlanAndAllocatesBoundedCloudBudget() {
        val plan = plan(
            teamBudget = 4_000,
            tasks = listOf(task("research", agentId = "research"), task("local", agentId = "local"))
        )

        val admission = AgentTeamPolicy.admit(plan, listOf(cloudCapability(), localCapability()))

        assertTrue(admission.accepted)
        assertEquals(4, admission.maxParallelTasks)
        assertEquals(4_000, admission.taskCloudBudgets.getValue("research"))
        assertEquals(0, admission.taskCloudBudgets.getValue("local"))
    }

    @Test
    fun rejectsPreloadedDependencyOutputWhenTaskContextsAreIsolated() {
        val context = SubAgentContext.test().copy(dependencyResults = mapOf("upstream" to "private output"))
        val plan = plan(tasks = listOf(task("child", context = context)))

        val admission = AgentTeamPolicy.admit(plan, emptyList())

        assertFalse(admission.accepted)
        assertTrue(admission.reason.contains("cannot preload dependency results"))
    }

    @Test
    fun rejectsCloudTeamWhenMinimumReserveExceedsParentBudget() {
        val plan = plan(teamBudget = 1_999, tasks = listOf(task("research", agentId = "research")))

        val admission = AgentTeamPolicy.admit(plan, listOf(cloudCapability()))

        assertFalse(admission.accepted)
        assertTrue(admission.reason.contains("minimum cloud reserve"))
    }

    @Test
    fun rejectsUnknownDependenciesAndUnsafeParallelLimit() {
        val dependencyPlan = plan(tasks = listOf(task("one", dependencies = listOf("missing"))))
        val unsafeParallelPlan = plan(maxParallelTasks = 5, tasks = listOf(task("one")))

        assertFalse(AgentTeamPolicy.admit(dependencyPlan, emptyList()).accepted)
        assertFalse(AgentTeamPolicy.admit(unsafeParallelPlan, emptyList()).accepted)
    }

    @Test
    fun rejectsKnownDependencyCycleBeforeExecution() {
        val cyclicPlan = plan(
            tasks = listOf(
                task("first", dependencies = listOf("second")),
                task("second", dependencies = listOf("first"))
            )
        )

        val admission = AgentTeamPolicy.admit(cyclicPlan, emptyList())

        assertFalse(admission.accepted)
        assertTrue(admission.reason.contains("dependency cycle"))
    }

    private fun plan(
        tasks: List<ProductionAgentOrchestrator.OrchestratorTask>,
        teamBudget: Int? = null,
        maxParallelTasks: Int = 4
    ) = ProductionAgentOrchestrator.OrchestratorPlan(
        tasks = tasks,
        projectId = "project-1",
        teamCloudTokenBudget = teamBudget,
        maxParallelTasks = maxParallelTasks
    )

    private fun task(
        id: String,
        agentId: String? = null,
        dependencies: List<String> = emptyList(),
        context: SubAgentContext = SubAgentContext.test().copy(projectId = "project-1", remainingCloudTokenBudget = 4_000)
    ) = ProductionAgentOrchestrator.OrchestratorTask(
        id = id,
        description = id,
        agentId = agentId,
        dependencies = dependencies,
        input = id,
        context = context
    )

    private fun cloudCapability() = SubAgentCapability(
        agentId = "research",
        displayName = "Research",
        description = "Cloud research",
        intentKeywords = listOf("research"),
        requiresCloud = true,
        costTier = SubAgentCapability.CostTier.MEDIUM
    )

    private fun localCapability() = SubAgentCapability(
        agentId = "local",
        displayName = "Local",
        description = "Local work",
        intentKeywords = listOf("local"),
        requiresCloud = false,
        costTier = SubAgentCapability.CostTier.FREE
    )
}
