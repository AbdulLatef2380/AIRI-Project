package com.airi.assistant.ai.remote

/** Keeps remote-model selection deterministic and preserves a valid active model. */
object RemoteModelSelectionPolicy {

    sealed interface Decision {
        data class Select(val modelId: String) : Decision
        data object RejectUnknown : Decision
        data object RejectBlank : Decision
    }

    fun decide(availableIds: Set<String>, requestedId: String): Decision = when {
        requestedId.isBlank() -> Decision.RejectBlank
        requestedId in availableIds -> Decision.Select(requestedId)
        else -> Decision.RejectUnknown
    }
}
