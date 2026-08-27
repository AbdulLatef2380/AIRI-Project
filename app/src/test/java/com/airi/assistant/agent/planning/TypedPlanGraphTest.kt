package com.airi.assistant.agent.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TypedPlanGraphTest {

    @Test
    fun addNode_rejectsBlankAndDuplicateIdsInsteadOfOverwritingNode() {
        val graph = TypedPlanGraph(goalId = "goal", description = "test")

        assertThrows(IllegalArgumentException::class.java) {
            graph.addNode(GoalNode(id = "", description = "blank", action = "noop"))
        }
        graph.addNode(GoalNode(id = "node", description = "first", action = "noop"))
        assertThrows(IllegalArgumentException::class.java) {
            graph.addNode(GoalNode(id = "node", description = "replacement", action = "noop"))
        }
        assertEquals("first", graph.allNodes().single().description)
    }

    @Test
    fun readyNodes_returnsStableIdentifierOrderForIndependentNodes() {
        val graph = TypedPlanGraph(goalId = "goal", description = "test")
        graph.addNode(GoalNode(id = "node-c", description = "c", action = "noop"))
        graph.addNode(GoalNode(id = "node-a", description = "a", action = "noop"))
        graph.addNode(GoalNode(id = "node-b", description = "b", action = "noop"))

        assertEquals(listOf("node-a", "node-b", "node-c"), graph.readyNodes().map { it.id })
    }
}
