package com.airi.assistant.ai

object ModelManager {
    private var currentModel: ModelInfo? = null
    private var loader: ModelLoader? = null

    fun setLoader(l: ModelLoader) {
        loader = l
    }

    fun load(model: ModelInfo, onReady: (Boolean) -> Unit) {
        loader?.loadModel(model) { success ->
            if (success) {
                currentModel = model
            }
            onReady(success)
        } ?: onReady(false)
    }

    fun getCurrent(): ModelInfo? {
        return currentModel
    }
}
