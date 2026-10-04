package com.airi.assistant.connector

/** Resolves the minimum OAuth scope set for actions currently allowed by user profiles. */
object ConnectorOAuthScopeResolver {
    fun requiredScopes(
        registry: ConnectorRegistry,
        profiles: ConnectorAccessProfileStore,
        runtimeId: String,
    ): Set<String> {
        val connector = registry.get(runtimeId) ?: return emptySet()
        return connector.agentActions()
            .asSequence()
            .filter { action ->
                val surfaceId = action.surfaceId ?: runtimeId
                ConnectorAccessPolicy.evaluate(profiles.get(surfaceId), action) == ConnectorAccessDecision.ALLOWED
            }
            .flatMap { action -> action.requiredOAuthScopes.asSequence() }
            .filter(String::isNotBlank)
            .toSortedSet()
    }
}
