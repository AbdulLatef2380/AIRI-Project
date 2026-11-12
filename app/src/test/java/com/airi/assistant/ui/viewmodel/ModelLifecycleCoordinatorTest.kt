package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLifecycleCoordinatorTest {
    @Test
    fun startIsIdempotentAndStopRunsOnce() {
        var starts = 0
        var stops = 0
        val coordinator = ModelLifecycleCoordinator(
            onStart = { starts++ },
            onStop = { stops++ },
        )

        coordinator.startOnce()
        coordinator.startOnce()
        coordinator.stopOnce()
        coordinator.stopOnce()

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test
    fun stopBeforeStartDoesNotStartOrInvokeStop() {
        var starts = 0
        var stops = 0
        val coordinator = ModelLifecycleCoordinator(
            onStart = { starts++ },
            onStop = { stops++ },
        )

        coordinator.stopOnce()
        coordinator.startOnce()

        assertEquals(0, starts)
        assertEquals(0, stops)
    }
}
