package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLoadStateTest {
    @Test fun newerSessionLoadTokenRejectsOlderCompletion() {
        val gate = LatestOperationGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun emptyIsDistinctFromFailedAndLoading() {
        assertFalse(SessionLoadState.Empty == SessionLoadState.Loading)
        assertFalse(SessionLoadState.Empty == SessionLoadState.Failed("db"))
        assertEquals(SessionLoadState.Empty, SessionLoadState.Empty)
    }

    @Test fun newerSuccessRejectsOlderFailureCompletion() {
        val gate = LatestOperationGate()
        val older = gate.begin()
        val newer = gate.begin()
        assertFalse(gate.isCurrent(older))
        assertTrue(gate.isCurrent(newer))
    }

    @Test fun newerCancellationStillInvalidatesOlderCompletion() {
        val gate = LatestOperationGate()
        val older = gate.begin()
        val newer = gate.begin()
        assertFalse(gate.isCurrent(older))
        assertTrue(gate.isCurrent(newer))
    }

    @Test fun readyStateKeepsPaginationMetadata() {
        val state = SessionLoadState.Ready(
            sessionId = "session",
            messageCount = 40,
            totalMessageCount = 120,
            hasOlder = true,
        )
        assertEquals(40, state.messageCount)
        assertEquals(120, state.totalMessageCount)
        assertTrue(state.hasOlder)
        assertFalse(state.isLoadingOlder)
        assertTrue(state.copy(isLoadingOlder = true).isLoadingOlder)
    }
}
