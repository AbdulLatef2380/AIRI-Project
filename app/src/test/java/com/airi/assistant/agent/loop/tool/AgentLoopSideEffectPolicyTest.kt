package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopSideEffectPolicyTest {

    @Test
    fun readAndConversationToolsRemainAvailableWithoutDurableContext() {
        listOf("read_screen", "web_search", "fetch_url", "memory_recall", "calendar_read", "ask_confirmation")
            .forEach { tool ->
                assertEquals(
                    AgentLoopSideEffectPolicy.Decision.ALLOW_READ,
                    AgentLoopSideEffectPolicy.decide(tool, hasDurableExecutionContext = false)
                )
            }
    }

    @Test
    fun writeAndLiveDeviceToolsFailClosedWithoutDurableContext() {
        listOf("calendar_create", "create_note", "set_alarm", "open_app", "tap", "type_text", "scroll_down", "go_back")
            .forEach { tool ->
                assertEquals(
                    AgentLoopSideEffectPolicy.Decision.DURABLE_CONTEXT_REQUIRED,
                    AgentLoopSideEffectPolicy.decide(tool, hasDurableExecutionContext = false)
                )
            }
    }

    @Test
    fun typedFutureRuntimeCanRequestAdmissionWithoutChangingToolClassification() {
        assertEquals(
            AgentLoopSideEffectPolicy.Decision.ALLOW_READ,
            AgentLoopSideEffectPolicy.decide("calendar_create", hasDurableExecutionContext = true)
        )
        assertTrue(AgentLoopSideEffectPolicy.blockedMessage("calendar_create").contains("approval session"))
    }
}
