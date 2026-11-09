package com.airi.assistant.agent.loop.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPhaseTrackerTest {
    @Test
    fun happyPathIsPlanActVerifyFinal() {
        val tracker = AgentPhaseTracker()
        assertEquals(AgentPhase.PLAN, tracker.current)
        assertTrue(tracker.moveTo(AgentPhase.ACT))
        assertTrue(tracker.moveTo(AgentPhase.VERIFY))
        assertTrue(tracker.moveTo(AgentPhase.FINAL))
        assertEquals(
            listOf(AgentPhase.PLAN, AgentPhase.ACT, AgentPhase.VERIFY, AgentPhase.FINAL),
            tracker.history,
        )
    }

    @Test
    fun verifyMayReturnToActForACorrectionTurn() {
        val tracker = AgentPhaseTracker()
        tracker.moveTo(AgentPhase.ACT)
        tracker.moveTo(AgentPhase.VERIFY)
        assertTrue(tracker.moveTo(AgentPhase.ACT))
        assertTrue(tracker.moveTo(AgentPhase.VERIFY))
        assertTrue(tracker.moveTo(AgentPhase.FINAL))
    }

    @Test
    fun illegalMovesAreRefusedAndFinalIsTerminal() {
        val tracker = AgentPhaseTracker()
        assertFalse(tracker.moveTo(AgentPhase.FINAL))
        assertFalse(tracker.moveTo(AgentPhase.VERIFY))
        assertEquals(AgentPhase.PLAN, tracker.current)
        tracker.moveTo(AgentPhase.ACT)
        tracker.moveTo(AgentPhase.VERIFY)
        tracker.moveTo(AgentPhase.FINAL)
        assertFalse(tracker.moveTo(AgentPhase.ACT))
        assertEquals(AgentPhase.FINAL, tracker.current)
    }
}
