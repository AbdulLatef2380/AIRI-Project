package com.airi.assistant.domain.release

import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateAvailabilityPolicyTest {
    @Test
    fun automaticUpdateChecksRemainDisabledWithoutAReleaseCatalog() {
        assertFalse(UpdateAvailabilityPolicy.automaticChecksAvailable)
    }
}
