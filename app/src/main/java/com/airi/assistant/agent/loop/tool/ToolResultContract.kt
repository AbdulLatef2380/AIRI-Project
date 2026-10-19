package com.airi.assistant.agent.loop.tool

/** Stable machine-readable codes shared by builtins, skills, and connectors. */
object ToolErrorCodes {
    const val INVALID_ARGUMENT = "invalid_argument"
    const val TOOL_NOT_FOUND = "tool_not_found"
    const val PERMISSION_DENIED = "permission_denied"
    const val AUTH_REQUIRED = "auth_required"
    const val NOT_CONNECTED = "not_connected"
    const val NETWORK_UNAVAILABLE = "network_unavailable"
    const val MEMORY_UNAVAILABLE = "memory_unavailable"
    const val MEMORY_SCOPE_UNAVAILABLE = "memory_scope_unavailable"
    const val APPROVAL_REQUIRED = "approval_required"
    const val TIMEOUT = "timeout"
    const val UNSUPPORTED = "unsupported"
    const val EXECUTION_FAILED = "execution_failed"
    const val SKILL_FAILED = "skill_failed"
    const val CONNECTOR_FAILED = "connector_failed"
    const val DUPLICATE_CALL = "duplicate_call"

    fun fromConnector(code: String): String = when (code.lowercase()) {
        NOT_CONNECTED, AUTH_REQUIRED, NETWORK_UNAVAILABLE, TIMEOUT,
        INVALID_ARGUMENT, PERMISSION_DENIED, APPROVAL_REQUIRED, UNSUPPORTED -> code.lowercase()
        "not_found" -> TOOL_NOT_FOUND
        "missing_param", "invalid_params", "bad_input" -> INVALID_ARGUMENT
        "unauthorized", "forbidden", "invalid_grant", "authorization_required", "provider_permission_denied" -> AUTH_REQUIRED
        "conflict" -> EXECUTION_FAILED
        "network_error", "api_error" -> CONNECTOR_FAILED
        else -> CONNECTOR_FAILED
    }

    fun fromSkillFailure(metadata: Map<String, String>, message: String?): String =
        when (metadata["error_code"]?.lowercase()) {
            AUTH_REQUIRED, PERMISSION_DENIED, NOT_CONNECTED, APPROVAL_REQUIRED ->
                metadata.getValue("error_code").lowercase()
            else -> when {
                metadata["failure_type"] == "authorization" -> PERMISSION_DENIED
                message?.contains("not connected", ignoreCase = true) == true -> NOT_CONNECTED
                else -> SKILL_FAILED
            }
        }
}

enum class ToolProvenance {
    BUILTIN,
    SKILL,
    CONNECTOR,
    SYSTEM,
}
