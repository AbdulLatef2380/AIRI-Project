package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadRequestPolicyTest {

    @Test
    fun onlyTheLatestLoadRequestMayUpdateTheVisibleModelState() {
        assertFalse(ModelLoadRequestPolicy.shouldApply(requestId = 4L, latestRequestId = 5L))
        assertTrue(ModelLoadRequestPolicy.shouldApply(requestId = 5L, latestRequestId = 5L))
    }

    @Test
    fun nativeFailureRestoresPreviousSelectionButDoesNotClaimItIsLoaded() {
        val previous = ModelUiState(
            selectedModelId = "previous-id",
            selectedModelName = "Previous model",
            selectedModelPath = "/models/previous.gguf",
            selectedModelSize = 10L,
            isModelReady = true
        )
        val failedAttempt = ModelUiState(
            selectedModelId = "bad-id",
            selectedModelName = "Bad model",
            selectedModelPath = "/models/bad.gguf",
            isModelLoading = true
        )

        val restored = ModelLoadRequestPolicy.restoreSelectionAfterLoadFailure(
            previous = previous,
            failed = failedAttempt,
            message = "native load failed",
            errorType = LoadErrorType.LOAD_FAILED,
            availableModels = emptyList()
        )

        assertEquals("previous-id", restored.selectedModelId)
        assertEquals("/models/previous.gguf", restored.selectedModelPath)
        assertFalse(restored.isModelReady)
        assertFalse(restored.isModelLoading)
        assertEquals(LoadErrorType.LOAD_FAILED, restored.loadErrorType)
    }

    @Test
    fun preflightFailureKeepsThePreviouslyLoadedModelReady() {
        val previous = ModelUiState(
            selectedModelId = "previous-id",
            selectedModelName = "Previous model",
            selectedModelPath = "/models/previous.gguf",
            isModelReady = true
        )

        val restored = ModelLoadRequestPolicy.rejectBeforeUnload(
            previous = previous,
            message = "invalid model",
            errorType = LoadErrorType.INVALID_FORMAT,
            availableModels = emptyList()
        )

        assertEquals("previous-id", restored.selectedModelId)
        assertTrue(restored.isModelReady)
        assertEquals("invalid model", restored.loadError)
    }
}
