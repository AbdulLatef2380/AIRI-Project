package com.airi.assistant.agent.reflection

import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.GraphSnapshot
import com.airi.assistant.agent.planning.NodeStatus
import com.airi.assistant.core.NodeExecutionRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAnswerVerifierTest {
    private fun node(id: String) = GoalNode(
        id = id,
        description = "test node",
        action = "test",
        params = emptyMap(),
        dependsOn = emptyList(),
        isCritical = true,
        expectedOutcome = "done"
    )

    @Test
    fun verifiesSuccessfulEvidenceTrail() {
        val n = node("n1")
        val done = n.also { it.status = NodeStatus.DONE }
        val snapshot = GraphSnapshot("g", "goal", 1, 1, 0, 0, listOf(done))
        val result = FinalAnswerVerifier.verify(listOf(NodeExecutionRecord(n, true, "done")), snapshot)
        assertTrue(result.verified)
    }

    @Test
    fun rejectsMissingOutputOrFailedNodes() {
        val n = node("n1")
        val failed = n.also { it.status = NodeStatus.FAILED }
        val snapshot = GraphSnapshot("g", "goal", 1, 0, 1, 0, listOf(failed))
        val result = FinalAnswerVerifier.verify(listOf(NodeExecutionRecord(n, false, "failed")), snapshot)
        assertFalse(result.verified)
        assertTrue(result.issues.isNotEmpty())
    }
}
