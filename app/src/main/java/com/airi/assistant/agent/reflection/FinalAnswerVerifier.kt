package com.airi.assistant.agent.reflection

import com.airi.assistant.agent.planning.GraphSnapshot
import com.airi.assistant.core.NodeExecutionRecord

/**
 * Execution-level final answer gate. It verifies that the answer has an
 * executable evidence trail; it deliberately does not claim factual truth.
 */
object FinalAnswerVerifier {
    fun verify(nodeResults: List<NodeExecutionRecord>, snapshot: GraphSnapshot): FinalAnswerVerification {
        val issues = buildList {
            if (nodeResults.isEmpty()) add("No execution evidence is available.")
            if (snapshot.failedNodes > 0) add("One or more plan nodes failed.")
            nodeResults.filter { it.success && it.message.isNullOrBlank() }
                .forEach { add("Node ${it.node.id} succeeded without an output message.") }
        }
        return FinalAnswerVerification(verified = issues.isEmpty(), issues = issues)
    }
}

data class FinalAnswerVerification(
    val verified: Boolean,
    val issues: List<String>
)
