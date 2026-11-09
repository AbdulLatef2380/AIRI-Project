package com.airi.assistant.connector

/**
 * User-visible, model-safe authorization projection. It deliberately contains
 * no access/refresh tokens or provider PII; identity is only a readiness label.
 */
data class ConnectorAuthorizationState(
    val connectorId: String,
    val identity: IdentityState,
    val requiredScopes: Set<String>,
    val grantedScopes: Set<String>,
    val surfaces: List<SurfaceState>,
    val status: AuthorizationStatus,
    val reasonCode: String? = null,
    val reason: String? = null,
) {
    val isAuthorized: Boolean
        get() = status == AuthorizationStatus.AUTHORIZED && surfaces.all { it.authorized }

    enum class IdentityState { UNKNOWN, SIGNED_OUT, SIGNED_IN }
    enum class AuthorizationStatus { NOT_CONFIGURED, IDENTITY_REQUIRED, SCOPE_REQUIRED, PROFILE_REQUIRED, AUTHORIZED, BLOCKED }

    data class SurfaceState(
        val surfaceId: String,
        val profile: ConnectorAccessProfile,
        val requiredScopes: Set<String> = emptySet(),
        val granted: Boolean,
        val authorized: Boolean,
        val reasonCode: String? = null,
    )
}

/** Pure decision helper shared by UI inventory, catalog and runtime adapters. */
object ConnectorAuthorizationProjection {
    fun forAction(
        connector: Connector,
        action: ConnectorAgentAction,
        profileStore: ConnectorAccessProfileStore,
    ): ConnectorAuthorizationState {
        val surfaceId = action.surfaceId ?: connector.id
        val profile = profileStore.get(surfaceId)
        val connectorState = connector.stateForAction(action)
        val requiredScopes = action.requiredOAuthScopes
        val identity = when {
            !connectorState.connected -> ConnectorAuthorizationState.IdentityState.SIGNED_OUT
            else -> ConnectorAuthorizationState.IdentityState.SIGNED_IN
        }
        val surface = ConnectorAuthorizationState.SurfaceState(
            surfaceId = surfaceId,
            profile = profile,
            requiredScopes = requiredScopes,
            granted = profile != ConnectorAccessProfile.NOT_CONFIGURED,
            authorized = connectorState.connected && connectorState.healthy &&
                profile.permits(action.permission),
            reasonCode = when {
                profile == ConnectorAccessProfile.NOT_CONFIGURED -> "profile_required"
                !connectorState.connected -> "identity_required"
                !connectorState.healthy -> "scope_or_health_required"
                !profile.permits(action.permission) -> "permission_denied"
                else -> null
            },
        )
        val status = when {
            profile == ConnectorAccessProfile.NOT_CONFIGURED -> ConnectorAuthorizationState.AuthorizationStatus.PROFILE_REQUIRED
            !connectorState.connected -> ConnectorAuthorizationState.AuthorizationStatus.IDENTITY_REQUIRED
            !connectorState.healthy -> ConnectorAuthorizationState.AuthorizationStatus.SCOPE_REQUIRED
            !profile.permits(action.permission) -> ConnectorAuthorizationState.AuthorizationStatus.BLOCKED
            else -> ConnectorAuthorizationState.AuthorizationStatus.AUTHORIZED
        }
        return ConnectorAuthorizationState(
            connectorId = connector.id,
            identity = identity,
            requiredScopes = requiredScopes,
            grantedScopes = if (connectorState.healthy) requiredScopes else emptySet(),
            surfaces = listOf(surface),
            status = status,
            reasonCode = surface.reasonCode,
            reason = connectorState.errorMessage ?: connectorState.statusLine.takeIf { it.isNotBlank() },
        )
    }
}
