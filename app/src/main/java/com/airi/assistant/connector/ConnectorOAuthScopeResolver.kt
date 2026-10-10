package com.airi.assistant.connector

/** Resolves the minimum OAuth scope set for actions currently allowed by user profiles. */
object ConnectorOAuthScopeResolver {
    fun requiredScopes(
        registry: ConnectorRegistry,
        profiles: ConnectorAccessProfileStore,
        runtimeId: String,
        targetSurfaceId: String? = null,
    ): Set<String> {
        val connector = registry.get(runtimeId) ?: return emptySet()
        return connector.agentActions()
            .asSequence()
            .filter { action ->
                val actionSurfaceId = action.surfaceId ?: runtimeId
                if (targetSurfaceId != null && actionSurfaceId != targetSurfaceId) return@filter false
                // Provider consent must cover every action the selected profile
                // permits, including writes that remain behind typed confirmation
                // at execution time. Confirmation is an execution gate, not a
                // reason to under-request the provider capability.
                profiles.get(actionSurfaceId).permits(action.permission)
            }
            .flatMap { action -> action.requiredOAuthScopes.asSequence() }
            .filter(String::isNotBlank)
            .toSortedSet()
    }
}
