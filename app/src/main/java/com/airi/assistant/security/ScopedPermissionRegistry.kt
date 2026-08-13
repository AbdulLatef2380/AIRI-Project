package com.airi.assistant.security

import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.ConcurrentHashMap

/**
 * ScopedPermissionRegistry — grants and revokes per-agent capability permissions.
 *
 * ── DESIGN ────────────────────────────────────────────────────────────────
 *
 *   Each sub-agent has a declared set of [AgentPermission] values that it
 *   claims to need. The registry enforces that an agent can only invoke a
 *   tool or capability for which it holds an active grant.
 *
 *   Grants are stored in-memory (process-scoped). Default grants for each
 *   known agent are seeded in [installDefaults]. The orchestrator or the
 *   PolicyEngine may expand or restrict grants at runtime.
 *
 * ── AUDIT ─────────────────────────────────────────────────────────────────
 *
 *   Every grant/revoke/check is logged with the AIRI_RUNTIME proof tag so the
 *   observability layer can reconstruct a permission audit trail.
 */
class ScopedPermissionRegistry {

    private val TAG = "ScopedPermissionRegistry"

    private val grants = ConcurrentHashMap<String, MutableSet<AgentPermission>>()

    enum class AgentPermission {
        // Data access
        READ_CALENDAR,
        WRITE_CALENDAR,
        READ_CONTACTS,
        READ_NOTIFICATIONS,
        POST_NOTIFICATIONS,

        // Device control
        SET_ALARM,
        OPEN_BROWSER,
        TRIGGER_INTENT,
        ACCESSIBILITY_ACTIONS,

        // Storage / memory
        READ_MEMORY,
        WRITE_MEMORY,
        READ_FILES,
        WRITE_FILES,

        // Network / external
        SEARCH_WEB,
        CALL_REMOTE_LLM,
        CALL_GITHUB_API,
        CALL_TELEGRAM_API,

        // Sensitive
        READ_LOCATION,
        READ_MICROPHONE,
        MANAGE_SKILLS,
        CLOUD_SYNC,

        // Internal orchestration
        SPAWN_SUBAGENT,
        WRITE_NOTES
    }

    /**
     * Seed default permission sets for well-known agents.
     * Call once from ServiceLocator.initSubAgentSystem().
     */
    fun installDefaults() {
        // Sub-agent principals — least-privilege per capability domain
        grant("coding_agent",       AgentPermission.READ_FILES, AgentPermission.WRITE_FILES, AgentPermission.SEARCH_WEB, AgentPermission.CALL_REMOTE_LLM)
        grant("research_agent",     AgentPermission.SEARCH_WEB, AgentPermission.CALL_REMOTE_LLM, AgentPermission.READ_MEMORY, AgentPermission.WRITE_MEMORY)
        grant("android_agent",      AgentPermission.ACCESSIBILITY_ACTIONS, AgentPermission.TRIGGER_INTENT, AgentPermission.OPEN_BROWSER)
        grant("productivity_agent", AgentPermission.READ_CALENDAR, AgentPermission.WRITE_CALENDAR, AgentPermission.SET_ALARM, AgentPermission.WRITE_NOTES, AgentPermission.POST_NOTIFICATIONS)
        grant("memory_agent",       AgentPermission.READ_MEMORY, AgentPermission.WRITE_MEMORY)

        // AP-SS: The main agent-loop principal. It orchestrates all user-requested
        // tool calls and must be granted the full tool permission surface —
        // individual tools are still rate-limited and firewall-checked; this grant
        // sets the maximum capability ceiling, not a blanket bypass.
        grant("agent_loop",
            AgentPermission.READ_CALENDAR,   AgentPermission.WRITE_CALENDAR,
            AgentPermission.READ_CONTACTS,   AgentPermission.READ_NOTIFICATIONS,
            AgentPermission.POST_NOTIFICATIONS,
            AgentPermission.SET_ALARM,       AgentPermission.OPEN_BROWSER,
            AgentPermission.TRIGGER_INTENT,  AgentPermission.ACCESSIBILITY_ACTIONS,
            AgentPermission.READ_MEMORY,     AgentPermission.WRITE_MEMORY,
            AgentPermission.READ_FILES,      AgentPermission.WRITE_FILES,
            AgentPermission.SEARCH_WEB,      AgentPermission.CALL_REMOTE_LLM,
            AgentPermission.CALL_GITHUB_API, AgentPermission.CALL_TELEGRAM_API,
            AgentPermission.READ_LOCATION,   AgentPermission.READ_MICROPHONE,
            AgentPermission.MANAGE_SKILLS,   AgentPermission.CLOUD_SYNC,
            AgentPermission.SPAWN_SUBAGENT,  AgentPermission.WRITE_NOTES
        )

        LoggingService.info(TAG, "AIRI_RUNTIME PERMISSION_DEFAULTS_INSTALLED agents=${grants.keys}")
    }

    /**
     * Grant one or more permissions to an agent.
     */
    fun grant(agentId: String, vararg permissions: AgentPermission) {
        val set = grants.getOrPut(agentId) { mutableSetOf() }
        permissions.forEach { p ->
            set.add(p)
            LoggingService.info(TAG, "AIRI_RUNTIME PERMISSION_GRANTED agent=$agentId permission=$p")
        }
    }

    /**
     * Revoke one or more permissions from an agent.
     */
    fun revoke(agentId: String, vararg permissions: AgentPermission) {
        val set = grants[agentId] ?: return
        permissions.forEach { p ->
            set.remove(p)
            LoggingService.warn(TAG, "AIRI_RUNTIME PERMISSION_REVOKED agent=$agentId permission=$p")
        }
    }

    /**
     * Revoke all permissions for an agent (sandbox reset).
     */
    fun revokeAll(agentId: String) {
        grants.remove(agentId)
        LoggingService.warn(TAG, "AIRI_RUNTIME PERMISSION_ALL_REVOKED agent=$agentId")
    }

    /**
     * Check if an agent holds a specific permission.
     * Logs a DENIED proof entry on failure.
     */
    fun check(agentId: String, permission: AgentPermission): Boolean {
        val has = grants[agentId]?.contains(permission) == true
        if (!has) {
            LoggingService.warn(TAG, "AIRI_RUNTIME PERMISSION_DENIED agent=$agentId permission=$permission")
        }
        return has
    }

    /**
     * Require a permission — throws [PermissionDeniedException] if not held.
     */
    fun require(agentId: String, permission: AgentPermission) {
        if (!check(agentId, permission)) {
            throw PermissionDeniedException(agentId, permission)
        }
    }

    /**
     * Returns the full grant set for an agent (defensive copy).
     */
    fun grantsFor(agentId: String): Set<AgentPermission> =
        grants[agentId]?.toSet() ?: emptySet()

    /**
     * Returns all grants as an immutable snapshot.
     */
    fun snapshot(): Map<String, Set<AgentPermission>> =
        grants.mapValues { it.value.toSet() }

    class PermissionDeniedException(agentId: String, permission: AgentPermission) :
        SecurityException("Agent '$agentId' does not hold permission: $permission")
}
