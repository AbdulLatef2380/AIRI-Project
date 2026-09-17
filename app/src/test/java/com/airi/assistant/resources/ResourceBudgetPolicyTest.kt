package com.airi.assistant.resources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceBudgetPolicyTest {
    @Test
    fun storageWarningStartsAt85Percent() {
        assertFalse(ResourceBudgetPolicy.storageWarning(84))
        assertTrue(ResourceBudgetPolicy.storageWarning(85))
        assertTrue(ResourceBudgetPolicy.storageWarning(100))
    }

    @Test
    fun ramWarningUsesTwentyPercentOr256MbFloor() {
        val twoGb = 2L * 1024L * 1024L * 1024L
        assertFalse(ResourceBudgetPolicy.ramWarning(500L * 1024L * 1024L, twoGb))
        assertTrue(ResourceBudgetPolicy.ramWarning(400L * 1024L * 1024L, twoGb))
        assertTrue(ResourceBudgetPolicy.ramWarning(255L * 1024L * 1024L, 512L * 1024L * 1024L))
        assertFalse(ResourceBudgetPolicy.ramWarning(300L * 1024L * 1024L, 512L * 1024L * 1024L))
    }

    @Test
    fun combinedWarningDoesNotHideEitherResource() {
        val message = ResourceBudgetPolicy.notificationMessage(true, true, 91)
        assertTrue(message.contains("91%"))
        assertTrue(message.contains("RAM"))
    }
}
