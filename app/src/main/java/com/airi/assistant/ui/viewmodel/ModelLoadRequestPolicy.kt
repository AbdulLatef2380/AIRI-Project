package com.airi.assistant.ui.viewmodel

/**
 * Ensures that only the latest explicit model-selection request may mutate the
 * visible model state. Native model loading is asynchronous and an older
 * callback can otherwise arrive after the user has selected another model.
 */
internal object ModelLoadRequestPolicy {
    fun shouldApply(requestId: Long, latestRequestId: Long): Boolean =
        requestId == latestRequestId

    fun rejectBeforeUnload(
        previous: ModelUiState,
        message: String,
        errorType: LoadErrorType,
        availableModels: List<com.airi.assistant.ai.ModelInfo>
    ): ModelUiState = previous.copy(
        isModelLoading = false,
        loadError = message,
        loadErrorType = errorType,
        loadProgress = -1,
        availableModels = availableModels
    )

    fun restoreSelectionAfterLoadFailure(
        previous: ModelUiState,
        failed: ModelUiState,
        message: String,
        errorType: LoadErrorType,
        availableModels: List<com.airi.assistant.ai.ModelInfo>
    ): ModelUiState = failed.copy(
        selectedModelId = previous.selectedModelId,
        selectedModelName = previous.selectedModelName,
        selectedModelPath = previous.selectedModelPath,
        selectedModelSize = previous.selectedModelSize,
        isModelLoading = false,
        isModelReady = false,
        loadError = message,
        loadErrorType = errorType,
        loadProgress = -1,
        availableModels = availableModels,
        capabilities = com.airi.assistant.ai.ModelCapabilities.textOnlyFallback()
    )
}
