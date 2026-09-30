package com.airi.assistant.connector.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IftttTriggerAdmissionPolicyTest {
    @Test
    fun triggerRequiresConnectedEnabledStateAndStoredKey() {
        assertTrue(IftttTriggerAdmissionPolicy.allows(true, false, true))
        assertFalse(IftttTriggerAdmissionPolicy.allows(false, false, true))
        assertFalse(IftttTriggerAdmissionPolicy.allows(true, true, true))
        assertFalse(IftttTriggerAdmissionPolicy.allows(true, false, false))
    }
}
