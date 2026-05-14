package com.airi.assistant.ai

object ModelManager {
    private var currentModel: ModelInfo? = null
    private var loader: ModelLoader? = null
    private var isLoading = false

    fun setLoader(l: ModelLoader) {
        loader = l
    }

    fun load(model: ModelInfo, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
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
            isLoading = false
            if (success) {
                ModelRegistry.addModel(model)
                currentModel = model
            }
            onReady(success)
        }
    }

    fun unload() {
        loader?.unload()
        currentModel = null
        isLoading = false
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
