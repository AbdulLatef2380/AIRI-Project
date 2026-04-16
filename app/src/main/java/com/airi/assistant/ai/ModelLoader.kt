package com.airi.assistant.ai

class ModelLoader(private val llamaManager: LlamaManager) {
    fun loadModel(model: ModelInfo, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        llamaManager.loadModel(model.path, onProgress, onReady)
    }

    fun unload() {
        llamaManager.unloadModel()
    }
}
