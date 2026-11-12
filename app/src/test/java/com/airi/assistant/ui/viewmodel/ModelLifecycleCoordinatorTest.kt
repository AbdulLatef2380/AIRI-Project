package com.airi.assistant.ui.viewmodel

import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLifecycleCoordinatorTest {
    @Test
    fun startIsIdempotentAndStopRunsOnce() {
        var starts = 0
        var stops = 0
        val coordinator = ModelLifecycleCoordinator(
            onStart = { starts++ },
            onLoad = {},
            onUnload = {},
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
            onLoad = {},
            onUnload = {},
            onStop = { stops++ },
        )

        coordinator.stopOnce()
        coordinator.startOnce()

        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test
    fun loadAndUnloadAreForwardedOnlyWhileStarted() {
        var loaded = 0
        var unloaded = 0
        val model = ModelInfo(
            name = "test",
            fileName = "test.gguf",
            size = 1L,
            quantization = "Q4",
            path = "/tmp/test.gguf",
            source = ModelSource.LOCAL_FILE,
        )
        val coordinator = ModelLifecycleCoordinator(
            onStart = {},
            onLoad = { loaded++ },
            onUnload = { unloaded++ },
            onStop = {},
        )

        coordinator.loadModel(model)
        coordinator.unloadModel()
        coordinator.startOnce()
        coordinator.loadModel(model)
        coordinator.unloadModel()
        coordinator.stopOnce()
        coordinator.loadModel(model)
        coordinator.unloadModel()

        assertEquals(1, loaded)
        assertEquals(1, unloaded)
    }
}
