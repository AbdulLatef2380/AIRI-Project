package com.airi.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentExecutionLifecycleTest {

    @Test
    fun activeGenerationAcceptsOnlyItsOwnCallbacks() {
        val first = AgentExecutionLifecycle().beginGeneration()
        val second = first.beginGeneration()

        assertFalse(second.accepts(first.generationId))
        assertTrue(second.accepts(second.generationId))
        assertEquals(AgentExecutionPhase.RUNNING, second.phase)
    }

    @Test
    fun cancellationRejectsCallbacksUntilTheGenerationIsResumedOrReplaced() {
        val running = AgentExecutionLifecycle().beginGeneration()
        val cancelled = running.requestCancellation()

        assertTrue(cancelled.isCancellationRequested)
        assertFalse(cancelled.accepts(running.generationId))

        val resumed = cancelled.resumeCurrentGeneration()
        assertFalse(resumed.isCancellationRequested)
        assertTrue(resumed.accepts(running.generationId))
    }

    @Test
    fun terminalOutcomesRejectFurtherCallbacks() {
        val running = AgentExecutionLifecycle().beginGeneration()

        assertFalse(running.markSucceeded().accepts(running.generationId))
        assertFalse(running.markFailed().accepts(running.generationId))
        assertFalse(running.requestCancellation().markCancelled().accepts(running.generationId))
    }

    @Test
    fun aNewGenerationRestoresRunningStateAfterCancellation() {
        val first = AgentExecutionLifecycle().beginGeneration()
        val second = first.requestCancellation().beginGeneration()

        assertEquals(first.generationId + 1, second.generationId)
        assertEquals(AgentExecutionPhase.RUNNING, second.phase)
        assertFalse(second.isCancellationRequested)
        assertFalse(second.accepts(first.generationId))
        assertTrue(second.accepts(second.generationId))
    }
}
