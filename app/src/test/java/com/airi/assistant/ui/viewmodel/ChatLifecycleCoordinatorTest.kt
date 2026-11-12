package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLifecycleCoordinatorTest {
    @Test
    fun startAndCloseAreIdempotent() {
        var starts = 0
        var closes = 0
        val coordinator = ChatLifecycleCoordinator(
            onStart = { starts++ },
            onClose = { closes++ },
        )

        coordinator.startOnce()
        coordinator.startOnce()
        coordinator.closeOnce()
        coordinator.closeOnce()
        coordinator.startOnce()

        assertEquals(1, starts)
        assertEquals(1, closes)
    }

    @Test
    fun closeBeforeStartPreventsStartup() {
        var starts = 0
        var closes = 0
        val coordinator = ChatLifecycleCoordinator(
            onStart = { starts++ },
            onClose = { closes++ },
        )

        coordinator.closeOnce()
        coordinator.startOnce()

        assertEquals(0, starts)
        assertEquals(1, closes)
    }
}
