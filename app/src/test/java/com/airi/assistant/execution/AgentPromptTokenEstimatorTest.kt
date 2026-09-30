package com.airi.assistant.execution

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTokenEstimatorTest {
    @Test
    fun includesHistoryThatIsNotInTheCurrentLocalPrompt() {
        val projection = ConversationRequestProjection(
            prompt = "current",
            conversationHistory = listOf(
                ExecutionRequest.ConversationTurn("user", "old context ".repeat(120)),
                ExecutionRequest.ConversationTurn("assistant", "earlier answer ".repeat(120)),
            ),
        )

        val estimate = AgentPromptTokenEstimator.estimate(
            systemPrompt = "system",
            localPrompt = "current",
            projection = projection,
        )

        assertTrue("history must contribute to the prompt estimate", estimate > 500)
    }
}
