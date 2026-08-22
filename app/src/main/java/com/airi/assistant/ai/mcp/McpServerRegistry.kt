package com.airi.assistant.ai.mcp

data class McpServerConfig(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val isEnabled: Boolean
)

object McpServerRegistry {
    private val servers = mutableMapOf<String, McpServerConfig>()

    fun register(config: McpServerConfig) {
        servers[config.id] = config
    }

    fun getActiveServers(): List<McpServerConfig> {
        return servers.values.filter { it.isEnabled }
    }

    fun clear() {
        servers.clear()
    }
}
