package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryClassifierLiveStateTest {
    @Test
    fun currentTimeQuestionUsesAgentRuntime() {
        assertEquals(true, QueryClassifier.requiresLiveRuntime("كم الساعة الآن؟"))
    }

    @Test
    fun connectorReadRequestUsesAgentRuntime() {
        assertEquals(true, QueryClassifier.requiresLiveRuntime("list my GitHub repositories"))
    }
}
