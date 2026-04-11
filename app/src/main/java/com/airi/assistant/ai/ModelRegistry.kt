package com.airi.assistant.ai

object ModelRegistry {
    private val models = mutableListOf<ModelInfo>()

    fun register(model: ModelInfo) {
        if (models.none { it.path == model.path }) {
            models.add(model)
        }
    }

    fun getModels(): List<ModelInfo> {
        return models
    }

    fun find(name: String): ModelInfo? {
        return models.find { it.name == name }
    }
    
    fun remove(model: ModelInfo) {
        models.remove(model)
    }
}
