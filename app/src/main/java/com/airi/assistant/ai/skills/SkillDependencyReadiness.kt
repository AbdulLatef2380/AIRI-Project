package com.airi.assistant.ai.skills

/**
 * Evaluates declared connector dependencies through the caller's live runtime
 * readiness source; persisted connection flags are not execution evidence.
 */
internal object SkillDependencyReadiness {
    fun areAvailable(
        dependencies: List<String>,
        isConnectorReady: (connectorId: String) -> Boolean,
    ): Boolean = dependencies
        .asSequence()
        .filter { it.startsWith(CONNECTOR_PREFIX) }
        .map { it.removePrefix(CONNECTOR_PREFIX) }
        .all { connectorId -> connectorId.isNotBlank() && isConnectorReady(connectorId) }

    fun areActionsAvailable(
        dependencies: List<String>,
        isActionReady: (connectorId: String, actionId: String) -> Boolean,
    ): Boolean = dependencies.all { dependency ->
        val parts = dependency.split(ACTION_SEPARATOR, limit = 2)
        parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() &&
            isActionReady(parts[0], parts[1])
    }

    private const val CONNECTOR_PREFIX = "connector:"
    private const val ACTION_SEPARATOR = ":"
}
