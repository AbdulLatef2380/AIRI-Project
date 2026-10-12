package com.airi.assistant.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentActivityBusTest {

    @Test
    fun attachExecutionId_usesSynchronousSnapshotForUnownedEvent() {
        val event = ActivityEvent(
            message = "connector started",
            category = ActivityCategory.CONNECTOR,
        )

        val owned = AgentActivityBus.attachExecutionId(event, "execution-a")

        assertEquals("execution-a", owned.executionId)
    }

    @Test
    fun attachExecutionId_preservesExplicitOwner() {
        val event = ActivityEvent(
            message = "tool completed",
            category = ActivityCategory.TOOL,
            executionId = "execution-a",
        )

        val owned = AgentActivityBus.attachExecutionId(event, "execution-b")

        assertEquals("execution-a", owned.executionId)
    }

    @Test
    fun attachExecutionId_doesNotInventOwnerWhenSnapshotIsEmpty() {
        val event = ActivityEvent(
            message = "background event",
            category = ActivityCategory.SYSTEM,
        )

        val owned = AgentActivityBus.attachExecutionId(event, "")

        assertNull(owned.executionId)
    }
}
