package com.airi.assistant.ai

import android.util.Log

object ModelRegistry {
    private val models = mutableListOf<ModelInfo>()
    private const val TAG = "AIRI"

    @Synchronized
    fun addModel(model: ModelInfo) {
        models.removeAll { it.id == model.id || it.path == model.path }
        models.add(model)
        Log.i(
            TAG,
            "MODEL_REGISTERED key=${model.id.hashCode().toUInt().toString(16)} " +
                "type=${model.type.label} local=${model.isLocal} sizeMb=${model.size / (1024 * 1024)}"
        )
    }

    @Synchronized
    fun register(model: ModelInfo) {
        addModel(model)
    }

    @Synchronized
    fun replaceAll(items: List<ModelInfo>) {
        models.clear()
        items.forEach { addModel(it) }
    }

    @Synchronized
    fun getAll(): List<ModelInfo> {
        return models.toList()
    }

    @Synchronized
    fun getAllModels(): List<ModelInfo> {
        return getAll()
    }

    @Synchronized
    fun getModels(): List<ModelInfo> {
        return getAll()
    }

    @Synchronized
    fun getById(id: String): ModelInfo? {
        return models.find { it.id == id }
    }

    @Synchronized
    fun find(name: String): ModelInfo? {
        return models.find { it.name == name }
    }

    @Synchronized
    fun remove(model: ModelInfo) {
        models.removeAll { it.id == model.id || it.path == model.path }
    }
}
