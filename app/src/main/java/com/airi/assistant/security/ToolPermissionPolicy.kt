package com.airi.assistant.security

internal object ToolPermissionPolicy {
    private val permissions = mapOf(
        "read_screen" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "open_app" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "tap" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "type_text" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "scroll_down" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "go_back" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "calendar_read" to ScopedPermissionRegistry.AgentPermission.READ_CALENDAR,
        "calendar_create" to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_update" to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_delete" to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "alarm_set" to ScopedPermissionRegistry.AgentPermission.SET_ALARM,
        "alarm_cancel" to ScopedPermissionRegistry.AgentPermission.SET_ALARM,
        "notification_post" to ScopedPermissionRegistry.AgentPermission.POST_NOTIFICATIONS,
        "search_web" to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "web_search" to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "fetch_url" to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "browser_open" to ScopedPermissionRegistry.AgentPermission.OPEN_BROWSER,
        "notes_create" to ScopedPermissionRegistry.AgentPermission.WRITE_NOTES,
        "notes_read" to ScopedPermissionRegistry.AgentPermission.READ_FILES,
        "create_note" to ScopedPermissionRegistry.AgentPermission.WRITE_NOTES,
        "file_read" to ScopedPermissionRegistry.AgentPermission.READ_FILES,
        "file_write" to ScopedPermissionRegistry.AgentPermission.WRITE_FILES,
        "memory_read" to ScopedPermissionRegistry.AgentPermission.READ_MEMORY,
        "memory_recall" to ScopedPermissionRegistry.AgentPermission.READ_MEMORY,
        "memory_write" to ScopedPermissionRegistry.AgentPermission.WRITE_MEMORY,
        "llm_call" to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,
        "remote_llm" to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,
        "github_api" to ScopedPermissionRegistry.AgentPermission.CALL_GITHUB_API,
        "telegram_send" to ScopedPermissionRegistry.AgentPermission.CALL_TELEGRAM_API,
        "accessibility_tap" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_type" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_scroll" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "intent_launch" to ScopedPermissionRegistry.AgentPermission.TRIGGER_INTENT,
        "spawn_subagent" to ScopedPermissionRegistry.AgentPermission.SPAWN_SUBAGENT,
        "cloud_sync" to ScopedPermissionRegistry.AgentPermission.CLOUD_SYNC
    )

    fun permissionFor(toolName: String): ScopedPermissionRegistry.AgentPermission? =
        permissions[toolName.trim().lowercase()]
}
