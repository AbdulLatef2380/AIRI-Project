package com.airi.assistant.ai

object ModelManager {
    private var currentModel: ModelInfo? = null
    private var loader: ModelLoader? = null

    fun setLoader(l: ModelLoader) {
        loader = l
    }

    fun load(model: ModelInfo, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        loader?.loadModel(model, onProgress) { success ->
            if (success) {
                ModelRegistry.addModel(model)
                currentModel = model
            }
            onReady(success)
        } ?: onReady(false)
    }

    fun unload() {
        loader?.unload()
        currentModel = null
    }

    fun getCurrent(): ModelInfo? = currentModel

    fun getAllModels(): List<ModelInfo> = ModelRegistry.getAll()
}
