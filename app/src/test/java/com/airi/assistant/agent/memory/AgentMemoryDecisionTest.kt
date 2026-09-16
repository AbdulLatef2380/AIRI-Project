package com.airi.assistant.agent.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryDecisionTest {
    @Test fun ordinaryConversationIsNotDurableMemory() {
        assertFalse(AgentMemoryDecision.shouldExtractFacts("user", "My name is Sam and I like tea."))
    }

    @Test fun explicitEnglishRequestIsAdmittedToAgentReview() {
        assertTrue(AgentMemoryDecision.shouldExtractFacts("user", "Remember this: my name is Sam."))
    }

    @Test fun explicitArabicRequestIsAdmittedToAgentReview() {
        assertTrue(AgentMemoryDecision.shouldExtractFacts("user", "احفظ هذا: أعيش في الخرطوم"))
    }

    @Test fun assistantMessagesCannotCreateMemory() {
        assertFalse(AgentMemoryDecision.shouldExtractFacts("assistant", "Remember this: the user likes tea."))
    }
}
