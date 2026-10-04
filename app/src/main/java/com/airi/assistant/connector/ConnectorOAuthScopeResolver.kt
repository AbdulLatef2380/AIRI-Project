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
                // Provider consent must cover every action the selected profile
                // permits, including writes that remain behind typed confirmation
                // at execution time. Confirmation is an execution gate, not a
                // reason to under-request the provider capability.
                profiles.get(surfaceId).permits(action.permission)
            }
            .flatMap { action -> action.requiredOAuthScopes.asSequence() }
            .filter(String::isNotBlank)
            .toSortedSet()
    }
}
