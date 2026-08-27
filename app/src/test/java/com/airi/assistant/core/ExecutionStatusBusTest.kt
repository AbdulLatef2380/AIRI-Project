package com.airi.assistant.core

import com.airi.assistant.ui.viewmodel.ExecutionStage
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionStatusBusTest {

    @Test
    fun eventsWithoutExplicitIdentityAreDropped() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted(
            goalDescription = "Active task",
            totalNodes = 2,
            executionId = "execution-a",
        )

        ExecutionStatusBus.onWaveStarted(
            nodeIds = listOf("stale-node"),
            nodeActions = listOf("stale action"),
        )
        ExecutionStatusBus.onGraphCompleted(success = true)

        val state = ExecutionStatusBus.status.value
        assertEquals("execution-a", state.executionId)
        assertEquals(ExecutionStage.PLANNING, state.executionStage)
        assertEquals("", state.activeNodeId)
        assertEquals(true, state.isWorking)
    }

    @Test
    fun eventsFromAnotherExecutionAreDropped() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted(
            goalDescription = "Active task",
            totalNodes = 1,
            executionId = "execution-a",
        )

        ExecutionStatusBus.onNodeCompleted(
            nodeId = "wrong-node",
            nodesCompleted = 1,
            executionId = "execution-b",
        )

        val state = ExecutionStatusBus.status.value
        assertEquals(0, state.nodesCompleted)
        assertEquals(ExecutionStage.PLANNING, state.executionStage)
    }
}
