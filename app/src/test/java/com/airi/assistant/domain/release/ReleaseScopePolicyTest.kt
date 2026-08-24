package com.airi.assistant.domain.release

import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseScopePolicyTest {
    @Test
    fun commercialSurfacesRemainDisabledDuringFeatureFreeze() {
        assertFalse(ReleaseScopePolicy.commercialSurfacesEnabled)
    }
}
