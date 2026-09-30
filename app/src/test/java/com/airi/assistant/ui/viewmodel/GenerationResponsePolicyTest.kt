package com.airi.assistant.ui.viewmodel

import com.airi.assistant.agent.loop.AgentLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationResponsePolicyTest {
    @Test
    fun onlyExplicitSuccessIsClassifiedAsSuccess() {
        assertEquals(
            GenerationResponseStatus.SUCCESS,
            GenerationResponsePolicy.classify(AgentLoop.TerminalState.SUCCESS),
        )
    }

    @Test
    fun timeoutIsNeverUpgradedByPartialStreamText() {
        assertEquals(
            GenerationResponseStatus.TIMEOUT,
            GenerationResponsePolicy.classify(AgentLoop.TerminalState.TIMEOUT),
        )
    }

    @Test
    fun providerOrAgentFailureRemainsFailure() {
        assertEquals(
            GenerationResponseStatus.FAILURE,
            GenerationResponsePolicy.classify(AgentLoop.TerminalState.FAILURE),
        )
    }

    @Test
    fun noResponseAndCancellationHaveDistinctStates() {
        assertEquals(
            GenerationResponseStatus.EMPTY_RESPONSE,
            GenerationResponsePolicy.classify(AgentLoop.TerminalState.NO_RESPONSE),
        )
        assertEquals(
            GenerationResponseStatus.CANCELLED,
            GenerationResponsePolicy.classify(AgentLoop.TerminalState.CANCELLED),
        )
    }

    @Test
    fun partialTextIsPersistableOnlyForFailureAndTimeout() {
        assertTrue(GenerationResponsePolicy.shouldPersistIncompleteResponse(GenerationResponseStatus.FAILURE, "partial"))
        assertTrue(GenerationResponsePolicy.shouldPersistIncompleteResponse(GenerationResponseStatus.TIMEOUT, "partial"))
        assertFalse(GenerationResponsePolicy.shouldPersistIncompleteResponse(GenerationResponseStatus.CANCELLED, "partial"))
        assertFalse(GenerationResponsePolicy.shouldPersistIncompleteResponse(GenerationResponseStatus.EMPTY_RESPONSE, "partial"))
        assertFalse(GenerationResponsePolicy.shouldPersistIncompleteResponse(GenerationResponseStatus.FAILURE, "  "))
    }
}
