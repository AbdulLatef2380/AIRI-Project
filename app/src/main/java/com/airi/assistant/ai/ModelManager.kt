package com.airi.assistant.ai

object ModelManager {
    private var currentModel: ModelInfo? = null
    private var loader: ModelLoader? = null
    private var isLoading = false
    private var operationId = 0L
    private var lastFailedModelId: String? = null

    fun setLoader(l: ModelLoader) {
        loader = l
    }

    fun load(model: ModelInfo, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val requestId = ++operationId
        if (isLoading) {
            onReady(false)
            return
        }
        val activeLoader = loader
        if (activeLoader == null) {
            onReady(false)
            return
        }
        isLoading = true
        activeLoader.unload()
        currentModel = null
        activeLoader.loadModel(model, onProgress) { success ->
            if (requestId == operationId) {
                isLoading = false
                if (success) {
                    ModelRegistry.addModel(model)
                    currentModel = model
                    lastFailedModelId = null
                } else {
                    lastFailedModelId = model.id
                }
                onReady(success)
            }
        }
    }

    fun unload() {
        operationId++
        loader?.unload()
        currentModel = null
        isLoading = false
    }

    /** Diagnostic state for startup recovery UI; explicit retry remains allowed. */
    fun lastFailedModelId(): String? = lastFailedModelId

    fun clearLastFailedModel() {
        lastFailedModelId = null
    }

    fun getCurrent(): ModelInfo? = currentModel

    fun getAllModels(): List<ModelInfo> = ModelRegistry.getAll()

    fun remove(model: ModelInfo) {
        if (currentModel?.id == model.id) {
            unload()
        }
        ModelRegistry.remove(model)
    }
}
