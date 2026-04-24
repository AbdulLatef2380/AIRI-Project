package com.airi.assistant.ai

import android.util.Log

object ModelRegistry {
    private val models = mutableListOf<ModelInfo>()
    private const val TAG = "AIRI_PROOF"

    fun addModel(model: ModelInfo) {
        models.removeAll { it.id == model.id || it.path == model.path }
        models.add(model)
        Log.i(TAG, "MODEL_REGISTERED name=${model.name} id=${model.id} type=${model.type.label} size=${model.size / (1024 * 1024)}MB path=${model.path}")
    }

    fun register(model: ModelInfo) {
        addModel(model)
    }

    fun replaceAll(items: List<ModelInfo>) {
        models.clear()
        items.forEach { addModel(it) }
    }

    fun getAll(): List<ModelInfo> {
        return models.toList()
    }

    fun getAllModels(): List<ModelInfo> {
        return getAll()
    }

    fun getModels(): List<ModelInfo> {
        return getAll()
    }

    fun getById(id: String): ModelInfo? {
        return models.find { it.id == id }
    }

    fun find(name: String): ModelInfo? {
        return models.find { it.name == name }
    }

    fun remove(model: ModelInfo) {
        models.removeAll { it.id == model.id || it.path == model.path }
    }
}
