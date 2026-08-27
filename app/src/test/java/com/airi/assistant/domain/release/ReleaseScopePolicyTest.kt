package com.airi.assistant.domain.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseScopePolicyTest {
    @Test
    fun commercialSurfacesRemainDisabledDuringFeatureFreeze() {
        assertFalse(ReleaseScopePolicy.commercialSurfacesEnabled)
    }

    @Test
    fun internalSurfacesAreAvailableOnlyInDevelopmentBuilds() {
        assertTrue(ReleaseScopePolicy.allowsInternalSurfaces(isDebugBuild = true))
        assertFalse(ReleaseScopePolicy.allowsInternalSurfaces(isDebugBuild = false))
    }
}
