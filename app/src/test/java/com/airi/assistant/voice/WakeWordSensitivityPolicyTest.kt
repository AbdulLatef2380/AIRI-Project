package com.airi.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WakeWordSensitivityPolicyTest {
    @Test
    fun clampsUnsafePreferenceValues() {
        assertEquals(WakeWordSensitivityPolicy.MIN, WakeWordSensitivityPolicy.clamp(-1f))
        assertEquals(WakeWordSensitivityPolicy.MAX, WakeWordSensitivityPolicy.clamp(2f))
    }

    @Test
    fun higherSensitivityLowersOpenWakeWordThreshold() {
        val low = WakeWordSensitivityPolicy.openWakeWordThreshold(WakeWordSensitivityPolicy.MIN)
        val high = WakeWordSensitivityPolicy.openWakeWordThreshold(WakeWordSensitivityPolicy.MAX)
        assertTrue(high < low)
        assertTrue(low in 0.40f..0.82f)
        assertTrue(high in 0.40f..0.82f)
    }
}
