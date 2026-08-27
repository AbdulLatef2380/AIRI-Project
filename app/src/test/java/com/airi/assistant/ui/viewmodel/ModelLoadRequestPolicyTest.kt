package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadRequestPolicyTest {

    @Test
    fun onlyTheLatestLoadRequestMayUpdateTheVisibleModelState() {
        assertFalse(ModelLoadRequestPolicy.shouldApply(requestId = 4L, latestRequestId = 5L))
        assertTrue(ModelLoadRequestPolicy.shouldApply(requestId = 5L, latestRequestId = 5L))
    }
}
