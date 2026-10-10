package com.airi.assistant.connector

/** User-selected ceiling for the capabilities of one catalog surface/runtime connector. */
enum class ConnectorAccessProfile {
    /** No explicit user decision: deny every agent action. */
    NOT_CONFIGURED,
    READ_ONLY,
    WRITE_ONLY,
    READ_WRITE,
    FULL_ACCESS;

    fun permits(permission: ConnectorPermissionLevel): Boolean = when (this) {
        NOT_CONFIGURED -> false
        READ_ONLY -> permission == ConnectorPermissionLevel.READ
        WRITE_ONLY -> permission == ConnectorPermissionLevel.WRITE
        READ_WRITE -> permission == ConnectorPermissionLevel.READ || permission == ConnectorPermissionLevel.WRITE
        FULL_ACCESS -> true
    }

    companion object {
        /** Hide choices that cannot authorize any action declared by this surface. */
        fun selectableFor(permissions: Set<ConnectorPermissionLevel>): List<ConnectorAccessProfile> = buildList {
            add(NOT_CONFIGURED)
            if (ConnectorPermissionLevel.READ in permissions) add(READ_ONLY)
            if (ConnectorPermissionLevel.WRITE in permissions) add(WRITE_ONLY)
            if (ConnectorPermissionLevel.READ in permissions && ConnectorPermissionLevel.WRITE in permissions) add(READ_WRITE)
            if (permissions.any { it == ConnectorPermissionLevel.DESTRUCTIVE || it == ConnectorPermissionLevel.ADMIN }) add(FULL_ACCESS)
        }
    }
}

enum class ConnectorAccessDecision { ALLOWED, NOT_GRANTED, CONFIRMATION_REQUIRED }

object ConnectorAccessPolicy {
    fun evaluate(
        profile: ConnectorAccessProfile,
        action: ConnectorAgentAction,
    ): ConnectorAccessDecision = when {
        !profile.permits(action.permission) -> ConnectorAccessDecision.NOT_GRANTED
        action.requiresConfirmation || action.permission == ConnectorPermissionLevel.DESTRUCTIVE ||
            action.permission == ConnectorPermissionLevel.ADMIN -> ConnectorAccessDecision.CONFIRMATION_REQUIRED
        else -> ConnectorAccessDecision.ALLOWED
    }
}

/** Small persistence boundary so runtime enforcement and UI share the same user grant. */
interface ConnectorAccessProfileStore {
    fun get(surfaceId: String): ConnectorAccessProfile
    fun set(surfaceId: String, profile: ConnectorAccessProfile)
    fun all(): Map<String, ConnectorAccessProfile>
    fun clear()
}

/** Deterministic implementation for unit tests and isolated callers. */
class InMemoryConnectorAccessProfileStore : ConnectorAccessProfileStore {
    private val profiles = LinkedHashMap<String, ConnectorAccessProfile>()

    @Synchronized
    override fun get(surfaceId: String): ConnectorAccessProfile =
        profiles[surfaceId]?.takeIf { it != ConnectorAccessProfile.NOT_CONFIGURED }
            ?: ConnectorAccessProfile.NOT_CONFIGURED

    @Synchronized
    override fun set(surfaceId: String, profile: ConnectorAccessProfile) {
        require(surfaceId.isNotBlank()) { "surfaceId must not be blank" }
        if (profile == ConnectorAccessProfile.NOT_CONFIGURED) profiles.remove(surfaceId)
        else profiles[surfaceId] = profile
    }

    @Synchronized
    override fun all(): Map<String, ConnectorAccessProfile> = profiles.toMap()

    @Synchronized
    override fun clear() = profiles.clear()
}
