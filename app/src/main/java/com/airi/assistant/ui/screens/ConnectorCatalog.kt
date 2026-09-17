package com.airi.assistant.ui.screens

import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorType

data class ConnectorPresentation(
    val category: String,
    val version: String,
    val projectAccess: String,
    val modelDependency: String,
    val capabilities: List<String>
)

/** User-facing explanations only; credentials and raw tokens never enter this catalog. */
fun ConnectorMeta.presentation(): ConnectorPresentation {
    val idLower = id.lowercase()
    return when {
        idLower.contains("github") -> ConnectorPresentation(
            category = "Development", version = "Managed", projectAccess = "Repository metadata and approved project actions",
            modelDependency = "Optional active model for natural-language requests",
            capabilities = listOf("Read repositories and issues", "Inspect project activity", "Support coding and repository workflows")
        )
        idLower.contains("google") || idLower.contains("gmail") || idLower.contains("drive") || idLower.contains("calendar") -> ConnectorPresentation(
            category = "Productivity", version = "Managed", projectAccess = "Only the Google resources authorized by the user",
            modelDependency = "Optional; the connector can execute structured actions",
            capabilities = listOf("Work with mail, files, or calendar data", "Support research and project organization", "Respect the connected account permissions")
        )
        idLower.contains("telegram") || idLower.contains("discord") || idLower.contains("slack") -> ConnectorPresentation(
            category = "Communication", version = "Managed", projectAccess = "Approved messages and notifications only",
            modelDependency = "Optional active model for drafting or routing",
            capabilities = listOf("Send approved notifications", "Connect project events to communication", "Keep external side effects policy-gated")
        )
        idLower.contains("mcp") -> ConnectorPresentation(
            category = "Extensions", version = "Managed", projectAccess = "Tools exposed by the configured MCP server",
            modelDependency = "Usually required for natural-language tool selection",
            capabilities = listOf("Expose external tools to AIRI", "Extend the agent without changing the core app", "Require explicit server configuration and permissions")
        )
        type == ConnectorType.LOCAL || type == ConnectorType.SYSTEM -> ConnectorPresentation(
            category = "Device & Local", version = "Built-in", projectAccess = "Only the device surface and permissions granted by the user",
            modelDependency = "Not required for direct actions",
            capabilities = listOf("Use local files or device capabilities", "Support offline workflows", "Respect Android runtime permissions")
        )
        type == ConnectorType.API -> ConnectorPresentation(
            category = "AI & APIs", version = "Managed", projectAccess = "The configured endpoint and approved request scope",
            modelDependency = "This connector is itself an API/model surface",
            capabilities = listOf("Send structured API requests", "Provide cloud model or service responses", "Use retries and error reporting from the connector layer")
        )
        else -> ConnectorPresentation(
            category = type.name.lowercase().replaceFirstChar { it.uppercase() }, version = "Managed",
            projectAccess = "Only the capabilities declared by this connector",
            modelDependency = "Depends on the selected workflow",
            capabilities = listOf(description)
        )
    }
}
