package com.airi.assistant.ai.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillReadinessTest {
    @Test
    fun disabledSkillIsExposedOnlyAfterExplicitEnablement() {
        val state = SkillReadiness.evaluate(true, false, true, true, true)
        assertTrue(state.registered)
        assertFalse(state.exposed)
        assertFalse(state.executable)
        assertEquals(SkillReadiness.BlockedReason.DISABLED, state.blockedReason)
        assertEquals(SkillReadiness.NextAction.ENABLE_SKILL, state.nextAction)
    }

    @Test
    fun connectorAndActionGatesAreDifferent() {
        val connector = SkillReadiness.evaluate(true, true, false, true, true)
        assertEquals(SkillReadiness.BlockedReason.CONNECTOR_NOT_READY, connector.blockedReason)
        assertEquals(SkillReadiness.NextAction.CONNECT_SERVICE, connector.nextAction)

        val action = SkillReadiness.evaluate(true, true, true, false, true)
        assertEquals(SkillReadiness.BlockedReason.ACTION_NOT_ALLOWED, action.blockedReason)
        assertEquals(SkillReadiness.NextAction.AUTHORIZE_ACTION, action.nextAction)
    }

    @Test
    fun runtimeGateDoesNotClaimExecutable() {
        val state = SkillReadiness.evaluate(true, true, true, true, true, runtimeValidated = false)
        assertTrue(state.ready)
        assertTrue(state.admitted)
        assertFalse(state.executable)
        assertEquals(SkillReadiness.BlockedReason.RUNTIME_GATE_PENDING, state.blockedReason)
    }

    @Test
    fun fullyValidatedSkillReachesEveryStage() {
        val state = SkillReadiness.evaluate(true, true, true, true, true)
        assertTrue(state.registered)
        assertTrue(state.ready)
        assertTrue(state.exposed)
        assertTrue(state.admitted)
        assertTrue(state.executable)
        assertEquals(null, state.blockedReason)
    }
}
