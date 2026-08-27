package com.airi.assistant.ui.activity

import com.airi.assistant.domain.event.AppEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class GlobalAgentEventDispatcherTest {

    @Test
    fun mapAppEvent_redactsAgentInputAndPreservesExecutionOwner() {
        val event = AppEvent.AgentExecutionStarted(
            input = "authorization: Bearer very-secret-token-value-1234567890 /data/user/0/com.airi/private.txt",
            traceId = "execution-1",
        )

        val projected = GlobalAgentEventDispatcher.mapAppEvent(event)

        assertNotNull(projected)
        assertEquals("execution-1", projected!!.executionId)
        assertFalse(projected.message.contains("very-secret-token-value-1234567890"))
        assertFalse(projected.message.contains("/data/user/0/com.airi/private.txt"))
    }

    @Test
    fun mapAppEvent_redactsProviderControlledErrorAndGenericInfo() {
        val failed = GlobalAgentEventDispatcher.mapAppEvent(
            AppEvent.AgentExecutionFailed(
                traceId = "execution-1",
                error = "cookie=session-secret-value password=hunter2",
            )
        )!!
        val generic = GlobalAgentEventDispatcher.mapAppEvent(
            AppEvent.GenericInfo("api_key=abcdefghijklmnop0123456789")
        )!!

        assertFalse(failed.message.contains("session-secret-value"))
        assertFalse(failed.message.contains("hunter2"))
        assertFalse(generic.message.contains("abcdefghijklmnop0123456789"))
    }
}
