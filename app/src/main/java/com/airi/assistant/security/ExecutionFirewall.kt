package com.airi.assistant.security

import com.airi.assistant.domain.logging.LoggingService

/**
 * ExecutionFirewall — intercepts tool-call and capability requests from
 * sub-agents and enforces [ScopedPermissionRegistry] grants before
 * allowing execution to proceed.
 *
 * ── INTEGRATION POINT ─────────────────────────────────────────────────────
 *
 *   The firewall is called by ProductionAgentOrchestrator before dispatching
 *   any tool call from a sub-agent. The call chain is:
 *
 *     SubAgent.execute() → ProductionAgentOrchestrator.executeSingle()
 *       → ExecutionFirewall.guard(agentId, toolName)
 *         → ScopedPermissionRegistry.require(agentId, mappedPermission)
 *           → tool dispatch if allowed, PermissionDeniedException otherwise
 *
 * ── TOOL → PERMISSION MAPPING ────────────────────────────────────────────
 *
 *   Tool names (as registered in ToolRegistry) are mapped to the narrowest
 *   AgentPermission that covers them. Unknown tools are DENIED by default
 *   (allowlist model, not blocklist).
 */
class ExecutionFirewall(
    private val registry: ScopedPermissionRegistry
) {

    private val TAG = "ExecutionFirewall"

    private val toolPermissionMap: Map<String, ScopedPermissionRegistry.AgentPermission> = mapOf(
        // Calendar
        "calendar_read"       to ScopedPermissionRegistry.AgentPermission.READ_CALENDAR,
        "calendar_create"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_update"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_delete"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,

        // Alarms
        "alarm_set"           to ScopedPermissionRegistry.AgentPermission.SET_ALARM,
        "alarm_cancel"        to ScopedPermissionRegistry.AgentPermission.SET_ALARM,

        // Notifications
        "notification_post"   to ScopedPermissionRegistry.AgentPermission.POST_NOTIFICATIONS,

        // Search / web
        "search_web"          to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "web_search"          to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "browser_open"        to ScopedPermissionRegistry.AgentPermission.OPEN_BROWSER,

        // Notes
        "notes_create"        to ScopedPermissionRegistry.AgentPermission.WRITE_NOTES,
        "notes_read"          to ScopedPermissionRegistry.AgentPermission.READ_FILES,

        // Files
        "file_read"           to ScopedPermissionRegistry.AgentPermission.READ_FILES,
        "file_write"          to ScopedPermissionRegistry.AgentPermission.WRITE_FILES,

        // Memory
        "memory_read"         to ScopedPermissionRegistry.AgentPermission.READ_MEMORY,
        "memory_write"        to ScopedPermissionRegistry.AgentPermission.WRITE_MEMORY,

        // Remote LLM
        "llm_call"            to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,
        "remote_llm"          to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,

        // Integrations
        "github_api"          to ScopedPermissionRegistry.AgentPermission.CALL_GITHUB_API,
        "telegram_send"       to ScopedPermissionRegistry.AgentPermission.CALL_TELEGRAM_API,

        // Accessibility
        "accessibility_tap"   to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_type"  to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_scroll" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "intent_launch"       to ScopedPermissionRegistry.AgentPermission.TRIGGER_INTENT,

        // Orchestration
        "spawn_subagent"      to ScopedPermissionRegistry.AgentPermission.SPAWN_SUBAGENT,
        "cloud_sync"          to ScopedPermissionRegistry.AgentPermission.CLOUD_SYNC
    )

    /**
     * Guard a tool call from [agentId].
     *
     * @throws ScopedPermissionRegistry.PermissionDeniedException if not allowed.
     * @throws UnknownToolException if [toolName] is not in the allowlist.
     */
    fun guard(agentId: String, toolName: String) {
        val permission = toolPermissionMap[toolName.lowercase()]
            ?: run {
                LoggingService.warn(TAG, "AIRI_PROOF FIREWALL_UNKNOWN_TOOL agent=$agentId tool=$toolName")
                throw UnknownToolException(agentId, toolName)
            }

        registry.require(agentId, permission)
        LoggingService.debug(TAG, "AIRI_PROOF FIREWALL_ALLOWED agent=$agentId tool=$toolName permission=$permission")
    }

    /**
     * Non-throwing guard — returns false if denied instead of throwing.
     */
    fun allows(agentId: String, toolName: String): Boolean {
        val permission = toolPermissionMap[toolName.lowercase()] ?: return false
        return registry.check(agentId, permission)
    }

    class UnknownToolException(agentId: String, toolName: String) :
        SecurityException("Agent '$agentId' tried to call unknown tool: '$toolName'")
}
