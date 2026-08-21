package com.airi.core.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanningContractsTest {

    @Test
    fun actionPlanRetainsOrderedStepsAndConfirmationRequirement() {
        val search = PlanStep.Search(
            id = "research",
            query = "Kotlin Multiplatform",
            expectedOutcome = "Official sources are available"
        )
        val response = PlanStep.Custom(
            id = "respond",
            action = "summarize",
            dependsOn = listOf(search.id)
        )

        val plan = ActionPlan(
            intent = "research",
            confidence = 0.92,
            steps = listOf(search, response),
            requiresConfirmation = true
        )

        assertEquals(listOf(search, response), plan.steps)
        assertTrue(plan.requiresConfirmation)
        assertEquals(listOf("research"), response.dependsOn)
    }

    @Test
    fun planStepVariantsPreservePortableExecutionMetadata() {
        val step = PlanStep.Navigate(
            id = "go-home",
            direction = PlanStep.Navigate.NavigationDirection.HOME,
            dependsOn = listOf("prepare"),
            expectedOutcome = "Home surface is visible"
        )

        assertIs<PlanStep.Navigate>(step)
        assertEquals(PlanStep.Navigate.NavigationDirection.HOME, step.direction)
        assertEquals("Home surface is visible", step.expectedOutcome)
        assertFalse(step.dependsOn.isEmpty())
    }

    @Test
    fun agentGoalUsesSharedPlanStepContracts() {
        val goal = AgentGoal(
            id = "goal-1",
            description = "Prepare a verified answer",
            steps = listOf(PlanStep.Custom(id = "reply", action = "respond"))
        )

        assertEquals("goal-1", goal.id)
        assertEquals(1, goal.steps.size)
        assertIs<PlanStep.Custom>(goal.steps.single())
    }
}
