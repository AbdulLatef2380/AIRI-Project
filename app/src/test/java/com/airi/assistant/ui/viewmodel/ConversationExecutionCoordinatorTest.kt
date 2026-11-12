package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExecutionCoordinatorTest {
    @Test
    fun dispatchPassesCommandUntilCoordinatorIsClosed() {
        val commands = mutableListOf<ConversationExecutionCoordinator.Command>()
        var teardownCalls = 0
        val coordinator = ConversationExecutionCoordinator(
            execute = { command -> commands += command; true },
            cancelExecution = {},
            teardown = { teardownCalls++ },
        )

        assertTrue(coordinator.dispatch(ConversationExecutionCoordinator.Command("hello", true)))
        coordinator.closeOnce()
        coordinator.closeOnce()
        assertFalse(coordinator.dispatch(ConversationExecutionCoordinator.Command("late", false)))

        assertEquals(listOf("hello"), commands.map { it.input })
        assertEquals(1, teardownCalls)
    }

    @Test
    fun cancelRunsOnlyBeforeCloseAndCloseDoesNotCancelAgain() {
        val clearFlags = mutableListOf<Boolean>()
        var teardownCalls = 0
        val coordinator = ConversationExecutionCoordinator(
            execute = { true },
            cancelExecution = { clearFlags += it },
            teardown = { teardownCalls++ },
        )

        assertTrue(coordinator.cancel(clearQueuedRequest = false))
        coordinator.closeOnce()
        assertFalse(coordinator.cancel(clearQueuedRequest = true))

        assertEquals(listOf(false), clearFlags)
        assertEquals(1, teardownCalls)
    }
}
