package com.airi.assistant.ui.viewmodel

import com.airi.assistant.R
import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import java.util.Locale

/**
 * Converts the machine-readable tool envelope into a safe, localized UI message.
 * Provider/connector prose is intentionally not surfaced here.
 */
data class ToolErrorPresentation(
    val code: String,
    val messageResId: Int,
    val formatArgs: List<String> = emptyList(),
)

object ToolErrorPresentationPolicy {
    private val envelope = Regex("^Error \\[([^,\\]]+)")

    fun resolve(toolName: String, toolResult: String): ToolErrorPresentation? {
        val code = envelope.find(toolResult)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return resolveCode(code, toolName)
    }

    fun resolveCode(code: String, toolName: String): ToolErrorPresentation {
        val normalized = code.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            ToolErrorCodes.AUTH_REQUIRED -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_auth_required,
                listOf(connectorLabel(toolName)),
            )
            ToolErrorCodes.NETWORK_UNAVAILABLE -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_network_unavailable,
            )
            ToolErrorCodes.MEMORY_UNAVAILABLE -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_memory_unavailable,
            )
            ToolErrorCodes.MEMORY_SCOPE_UNAVAILABLE -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_memory_scope_unavailable,
            )
            ToolErrorCodes.PERMISSION_DENIED -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_permission_denied,
            )
            ToolErrorCodes.APPROVAL_REQUIRED -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_approval_required,
            )
            ToolErrorCodes.NOT_CONNECTED -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_not_connected,
                listOf(connectorLabel(toolName)),
            )
            ToolErrorCodes.TOOL_NOT_FOUND -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_tool_not_found,
            )
            ToolErrorCodes.UNSUPPORTED -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_unsupported,
            )
            else -> ToolErrorPresentation(
                normalized,
                R.string.tool_error_generic,
            )
        }
    }

    private fun connectorLabel(toolName: String): String {
        val name = toolName.lowercase(Locale.ROOT)
        return when {
            name.contains("calendar") -> "Google Calendar"
            name.contains("gmail") || name.contains("google") || name.contains("drive") -> "Google"
            name.contains("github") -> "GitHub"
            name.contains("slack") -> "Slack"
            name.contains("notion") -> "Notion"
            name.contains("telegram") -> "Telegram"
            name.contains("zapier") -> "Zapier"
            name.contains("ifttt") -> "IFTTT"
            else -> "the required service"
        }
    }
}
