package com.airi.assistant.ai

class ModelLoader(private val llamaManager: LlamaManager) {
    fun loadModel(model: ModelInfo, onReady: (Boolean) -> Unit) {
        llamaManager.loadModel(model.path, onReady)
    }
}
