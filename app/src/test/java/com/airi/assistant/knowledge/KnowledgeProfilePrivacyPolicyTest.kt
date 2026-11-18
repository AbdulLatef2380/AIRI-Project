package com.airi.assistant.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeProfilePrivacyPolicyTest {
    @Test
    fun localOnlyProfilesAreExcludedFromCloudRetrieval() {
        assertFalse(
            KnowledgeProfilePrivacyPolicy.isAllowed(
                profilePrivacyLevel = KnowledgeProfilePrivacyPolicy.DEFAULT_LEVEL,
                maxPrivacyLevel = KnowledgeProfilePrivacyPolicy.CLOUD_CONTEXT_LEVEL,
            )
        )
    }

    @Test
    fun explicitlyCloudApprovedProfilesAreEligibleForCloudRetrieval() {
        assertTrue(
            KnowledgeProfilePrivacyPolicy.isAllowed(
                profilePrivacyLevel = KnowledgeProfilePrivacyPolicy.CLOUD_CONTEXT_LEVEL,
                maxPrivacyLevel = KnowledgeProfilePrivacyPolicy.CLOUD_CONTEXT_LEVEL,
            )
        )
    }

    @Test
    fun malformedLevelsAreClampedToTheKnownRange() {
        assertFalse(KnowledgeProfilePrivacyPolicy.isAllowed(profilePrivacyLevel = 99, maxPrivacyLevel = 1))
        assertTrue(KnowledgeProfilePrivacyPolicy.isAllowed(profilePrivacyLevel = -1, maxPrivacyLevel = 0))
    }
}
