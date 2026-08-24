package com.airi.assistant.domain.release

import kotlin.test.Test
import kotlin.test.assertFalse

class ReleaseScopePolicyTest {
    @Test
    fun commercialSurfacesRemainDisabledDuringFeatureFreeze() {
        assertFalse(ReleaseScopePolicy.commercialSurfacesEnabled)
    }
}
