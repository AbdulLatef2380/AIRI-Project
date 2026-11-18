package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityInventoryProjectionTest {
    @Test
    fun readScreenBlockedReasonAndNextActionReachInventory() {
        val catalog = RuntimeToolCatalog.assemble(
            builtins = listOf(BuiltinTools.READ_SCREEN),
            skills = emptyList(),
            connectors = emptyList(),
            intent = CapabilityIntentDetector.Intent(),
            statusOverrides = mapOf(
                "read_screen" to RuntimeToolCatalog.StatusOverride(
                    readiness = RuntimeToolContract.Readiness.PERMISSION_REQUIRED,
                    reason = "Accessibility service is not connected",
                    nextAction = "enable_accessibility",
                ),
            ),
            requestText = "read screen",
        )
        val inventory = RuntimeCapabilityInventory.from(
            catalog = catalog,
            dispatcherAdmittedNames = emptySet(),
        )

        val entry = inventory.entries.single()
        assertTrue(entry.availability == RuntimeCapabilityInventory.Availability.PERMISSION_REQUIRED)
        assertTrue(entry.nextAction == "enable_accessibility")
        assertTrue(inventory.promptBlock("read screen").contains("next_action=enable_accessibility"))
    }
}
