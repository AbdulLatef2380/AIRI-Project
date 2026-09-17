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

/**
 * Presentation adapter: it only explains metadata declared by the connector contract.
 * It never invents provider-specific permissions, actions, or capabilities.
 */
fun ConnectorMeta.presentation(): ConnectorPresentation {
    val category = when (type) {
        ConnectorType.API -> "AI & APIs"
        ConnectorType.APP -> "Apps & Services"
        ConnectorType.LOCAL -> "Device & Local"
        ConnectorType.MCP -> "Extensions"
        ConnectorType.SYSTEM -> "System"
    }
    val projectAccess = when (type) {
        ConnectorType.LOCAL, ConnectorType.SYSTEM -> "The device surface and Android permissions granted by the user"
        ConnectorType.MCP -> "The tools exposed by the configured MCP server"
        else -> "The endpoint and resources authorized by the user"
    }
    val modelDependency = when (type) {
        ConnectorType.API -> "This connector provides an API or model surface"
        else -> "The connector does not declare a model requirement in its public metadata"
    }
    val capabilities = buildList {
        add(description)
        if (tags.isNotEmpty()) add("Declared tags: ${tags.joinToString()}")
    }
    return ConnectorPresentation(
        category = category,
        version = "Contract-managed",
        projectAccess = projectAccess,
        modelDependency = modelDependency,
        capabilities = capabilities
    )
}
