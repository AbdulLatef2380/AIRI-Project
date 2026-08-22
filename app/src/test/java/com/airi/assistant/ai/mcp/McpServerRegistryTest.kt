package com.airi.assistant.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class McpServerRegistryTest {

    @Before
    fun setUp() {
        McpServerRegistry.clear()
    }

    @Test
    fun registersAndRetrievesActiveMcpServers() {
        McpServerRegistry.register(McpServerConfig("mcp-1", "Filesystem MCP", "http://localhost:8080/mcp", true))
        McpServerRegistry.register(McpServerConfig("mcp-2", "Disabled MCP", "http://localhost:8081/mcp", false))

        val active = McpServerRegistry.getActiveServers()
        assertEquals(1, active.size)
        assertEquals("mcp-1", active[0].id)
    }
}
