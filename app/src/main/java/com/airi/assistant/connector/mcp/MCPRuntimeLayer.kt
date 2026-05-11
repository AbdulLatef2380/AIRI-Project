package com.airi.assistant.connector.mcp

import android.util.Log
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.ConnectorOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MCPRuntimeLayer — Model Context Protocol runtime manager.
 *
 * Manages the full lifecycle of MCP server connections and exposes a
 * unified tool-invocation API that the agent pipeline and [ToolResolver] use.
 *
 * ── ARCHITECTURE ─────────────────────────────────────────────────────────────
 *
 *   MCPRuntimeLayer
 *     ├── ConnectorRegistry  (all registered McpConnectors)
 *     ├── MCPToolRegistry    (flat tool→connector mapping)
 *     ├── HealthMonitor      (per-connector health polling)
 *     └── Capability mapper  (what tools are available right now)
 *
 * ── MCP SERVER TYPES ─────────────────────────────────────────────────────────
 *
 *   1. [InMemoryMcpConnector] — built-in demo server (always available)
 *   2. [HttpSseMcpConnector]  — remote HTTP+SSE MCP server (configurable)
 *   3. Custom subclasses      — any [McpConnector] registered at startup
 *
 * ── TOOL INVOCATION ──────────────────────────────────────────────────────────
 *
 *   invokeTool("echo", "hello world") →
 *     1. look up tool in [MCPToolRegistry]
 *     2. route to owning McpConnector
 *     3. call connector.execute(ConnectorInput(action="invoke_tool", params=...))
 *     4. return [ConnectorOutput]
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────────
 *
 *   Call [start] once from Application.onCreate.
 *   All registered MCP servers are connected; unavailable ones are logged.
 *   [stop] disconnects all servers cleanly.
 */
class MCPRuntimeLayer(
    private val connectorRegistry: ConnectorRegistry,
) {

    private val TAG   = "MCPRuntimeLayer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // ── Tool registry ─────────────────────────────────────────────────────────

    data class MCPToolEntry(
        val toolName:    String,
        val description: String,
        val connectorId: String,
        val schema:      Map<String, String>,
    )

    private val _toolRegistry = MutableStateFlow<List<MCPToolEntry>>(emptyList())
    val toolRegistry: StateFlow<List<MCPToolEntry>> = _toolRegistry.asStateFlow()

    // ── Server registry ───────────────────────────────────────────────────────

    data class MCPServerStatus(
        val connectorId: String,
        val name:        String,
        val connected:   Boolean,
        val toolCount:   Int,
        val lastErrorMs: Long?,
    )

    private val _serverStatuses = MutableStateFlow<List<MCPServerStatus>>(emptyList())
    val serverStatuses: StateFlow<List<MCPServerStatus>> = _serverStatuses.asStateFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "MCPRuntimeLayer starting…")
            connectAllServers()
            rebuildToolRegistry()
            Log.i(TAG, "MCPRuntimeLayer ready — ${_toolRegistry.value.size} tools registered")
        }
    }

    fun stop() {
        scope.launch {
            allMcpConnectors().forEach { connector ->
                runCatching { connector.disconnect() }
                    .onFailure { Log.w(TAG, "disconnect error for ${connector.id}: ${it.message}") }
            }
            Log.i(TAG, "MCPRuntimeLayer stopped")
        }
    }

    // ── Tool invocation ───────────────────────────────────────────────────────

    /**
     * Invoke a named MCP tool across any registered MCP server.
     *
     * @param toolName  The tool name (e.g. "echo", "file_read").
     * @param input     Free-text input for the tool.
     * @param params    Typed key-value parameters.
     * @return [ConnectorOutput.Success] or [ConnectorOutput.Failure].
     */
    suspend fun invokeTool(
        toolName:  String,
        input:     String                = "",
        params:    Map<String, String>   = emptyMap(),
        timeoutMs: Long                  = DEFAULT_INVOKE_TIMEOUT_MS,
    ): ConnectorOutput {
        val entry = _toolRegistry.value.firstOrNull { it.toolName.equals(toolName, ignoreCase = true) }
            ?: return ConnectorOutput.Failure(
                code      = "tool_not_found",
                message   = "MCP tool '$toolName' not found. Available: ${availableToolNames().take(10).joinToString()}",
                retryable = false,
            )

        val connector = allMcpConnectors().firstOrNull { it.id == entry.connectorId }
            ?: return ConnectorOutput.Failure(
                code    = "connector_gone",
                message = "MCP server '${entry.connectorId}' is no longer registered",
            )

        val invokeInput = com.airi.assistant.connector.ConnectorInput(
            action = "invoke_tool",
            text   = input,
            params = params + mapOf("tool" to toolName),
        )

        val result = withTimeoutOrNull(timeoutMs) {
            runCatching { connector.execute(invokeInput) }.getOrElse { e ->
                ConnectorOutput.Failure(
                    code      = "invoke_exception",
                    message   = "${e.javaClass.simpleName}: ${e.message}",
                    retryable = true,
                )
            }
        } ?: ConnectorOutput.Failure(
            code      = "timeout",
            message   = "MCP tool '$toolName' timed out after ${timeoutMs}ms",
            retryable = true,
        )

        Log.d(TAG, "MCP_INVOKE tool=$toolName connector=${entry.connectorId} success=${result is ConnectorOutput.Success}")
        return result
    }

    /**
     * List all available tool names across all connected servers.
     */
    fun availableToolNames(): List<String> =
        _toolRegistry.value.map { it.toolName }

    /**
     * Get the full capability map: tool → description.
     */
    fun capabilityMap(): Map<String, String> =
        _toolRegistry.value.associate { it.toolName to it.description }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun connectAllServers() = mutex.withLock {
        val mcpConnectors = allMcpConnectors()
        val statuses = mcpConnectors.map { connector ->
            val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runCatching { connector.connect() }.getOrNull()
            }
            val connected = state?.connected == true
            Log.i(TAG, "MCP_CONNECT id=${connector.id} connected=$connected")
            MCPServerStatus(
                connectorId = connector.id,
                name        = connector.name,
                connected   = connected,
                toolCount   = if (connected) connector.tools.size else 0,
                lastErrorMs = if (!connected) System.currentTimeMillis() else null,
            )
        }
        _serverStatuses.value = statuses
    }

    private fun rebuildToolRegistry() {
        val tools = allMcpConnectors()
            .filter { c -> _serverStatuses.value.find { it.connectorId == c.id }?.connected == true }
            .flatMap { connector ->
                connector.tools.map { tool ->
                    MCPToolEntry(
                        toolName    = tool.name,
                        description = tool.description,
                        connectorId = connector.id,
                        schema      = tool.schema,
                    )
                }
            }
        _toolRegistry.value = tools
        Log.i(TAG, "MCPToolRegistry rebuilt: ${tools.size} tools from ${_serverStatuses.value.count { it.connected }} servers")
    }

    private fun allMcpConnectors(): List<McpConnector> {
        return runCatching {
            connectorRegistry.all()
                .filterIsInstance<McpConnector>()
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS        = 8_000L
        private const val DEFAULT_INVOKE_TIMEOUT_MS = 15_000L
    }
}
