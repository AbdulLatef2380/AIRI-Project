package com.airi.assistant.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferenceProfileTest {

    @Test
    fun modelContextRequiresExplicitSharing() {
        val profile = UserPreferenceProfile(
            workContext = "Android developer",
            currentGoal = "Close the next release",
            shareWithResponses = false
        )

        assertEquals("", profile.modelContext())
    }

    @Test
    fun modelContextNormalizesAndBoundsOnlyExplicitFields() {
        val longGoal = "g".repeat(UserPreferenceProfile.MAX_FIELD_CHARS + 20)
        val profile = UserPreferenceProfile(
            workContext = "  Android\n developer  ",
            currentGoal = longGoal,
            shareWithResponses = true
        )

        val context = profile.modelContext()

        assertTrue(context.contains("Work context: Android developer"))
        assertTrue(context.contains("Current goal: ${"g".repeat(UserPreferenceProfile.MAX_FIELD_CHARS)}"))
        assertFalse(context.contains("\n developer"))
        assertTrue(context.length < 400)
    }
}
