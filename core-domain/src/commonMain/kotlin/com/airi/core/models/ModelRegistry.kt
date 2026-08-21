package com.airi.core.models

enum class ModelExecutionMode {
    LOCAL,
    REMOTE
}

enum class ModelAvailability {
    READY,
    UNAVAILABLE,
    REQUIRES_CONFIGURATION
}

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val executionMode: ModelExecutionMode,
    val availability: ModelAvailability,
    val unavailableReason: String? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(
            (availability == ModelAvailability.READY) == unavailableReason.isNullOrBlank()
        ) { "unavailableReason must be present exactly when the model is not ready" }
    }
}

sealed interface ModelSelectionResult {
    data class Selected(val model: ModelDescriptor) : ModelSelectionResult
    data class Rejected(val requestedModelId: String, val reason: String) : ModelSelectionResult
}

object ModelRegistry {

    fun ordered(models: Iterable<ModelDescriptor>): List<ModelDescriptor> =
        models.sortedWith(compareBy<ModelDescriptor> { it.displayName.lowercase() }.thenBy { it.id })

    fun select(models: Iterable<ModelDescriptor>, requestedModelId: String): ModelSelectionResult {
        val model = models.firstOrNull { it.id == requestedModelId }
            ?: return ModelSelectionResult.Rejected(requestedModelId, "unknown_model")
        if (model.availability != ModelAvailability.READY) {
            return ModelSelectionResult.Rejected(
                requestedModelId,
                model.unavailableReason ?: "model_not_ready"
            )
        }
        return ModelSelectionResult.Selected(model)
    }

    fun defaultReady(models: Iterable<ModelDescriptor>): ModelDescriptor? =
        ordered(models).firstOrNull { it.availability == ModelAvailability.READY }
}
