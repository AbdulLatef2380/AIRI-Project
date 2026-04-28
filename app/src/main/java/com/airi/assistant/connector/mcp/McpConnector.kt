package com.airi.assistant.connector.mcp

import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Abstract base for Model Context Protocol connectors.
 *
 * MCP servers are an extensible plugin surface — each remote MCP server
 * maps to one [McpConnector]. This base class handles the boilerplate
 * (state flow, lifecycle, action dispatch) so concrete subclasses only
 * implement the transport (stdio, http+sse, etc.).
 *
 * The default [InMemoryMcpConnector] subclass is provided so the UI tab
 * is never empty — users can see the MCP capability is wired in even
 * before they configure a real MCP server.
 */
abstract class McpConnector(
    final override val id: String,
    final override val name: String,
    final override val description: String,
    val tools: List<McpTool>,
) : Connector {

    final override val type: ConnectorType = ConnectorType.MCP

    private val _state = MutableStateFlow(
        ConnectorState(
            connected = false, healthy = false,
            statusLine = "Not connected",
        )
    )

    final override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = tools.map { it.name },
    )

    final override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    final override suspend fun connect(): ConnectorState {
        val ok = runCatching { handshake() }.getOrDefault(false)
        _state.value = ConnectorState(
            connected = ok, healthy = ok,
            statusLine = if (ok) "${tools.size} tool(s) available" else "Handshake failed",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    final override suspend fun disconnect() {
        runCatching { teardown() }
        _state.value = _state.value.copy(
            connected = false, healthy = false, statusLine = "Disconnected",
            lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    final override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        if (!_state.value.connected) {
            return ConnectorOutput.Failure(
                code = "not_connected", message = "MCP server '$id' is not connected",
                retryable = true,
            )
        }
        if (input.action != "invoke_tool") {
            return ConnectorOutput.Failure(
                code = "unknown_action",
                message = "McpConnector accepts only 'invoke_tool' (got '${input.action}')",
            )
        }
        val toolName = input.params["tool"] ?: return ConnectorOutput.Failure(
            code = "bad_input", message = "Missing 'tool' param",
        )
        val tool = tools.firstOrNull { it.name == toolName }
            ?: return ConnectorOutput.Failure(
                code = "unknown_tool", message = "Tool '$toolName' not found on '$id'",
            )
        return runCatching { invoke(tool, input.text, input.params) }
            .getOrElse { e ->
                ConnectorOutput.Failure(
                    code = "tool_failed",
                    message = "${tool.name}: ${e.message ?: e.javaClass.simpleName}",
                    retryable = true,
                )
            }
    }

    /** Subclass: open the transport, perform MCP handshake, return true
     *  on success. Throws on hard errors (caller catches). */
    protected abstract suspend fun handshake(): Boolean

    /** Subclass: close transport. Best-effort. */
    protected abstract suspend fun teardown()

    /** Subclass: actually invoke the named tool. */
    protected abstract suspend fun invoke(
        tool: McpTool,
        text: String,
        params: Map<String, String>,
    ): ConnectorOutput

    data class McpTool(
        val name: String,
        val description: String,
        val schema: Map<String, String> = emptyMap(),
    )
}

/**
 * Built-in placeholder MCP connector. Always handshake-succeeds and
 * exposes a tiny `echo` tool so users can verify the MCP plumbing
 * end-to-end without configuring an external server.
 */
class InMemoryMcpConnector : McpConnector(
    id = "mcp_local_demo",
    name = "Local MCP (demo)",
    description = "Built-in MCP demo with an `echo` tool. Replace with a real MCP server.",
    tools = listOf(
        McpTool(name = "echo", description = "Echoes the provided text back.")
    ),
) {
    override suspend fun handshake(): Boolean = true
    override suspend fun teardown() { /* no-op */ }
    override suspend fun invoke(
        tool: McpTool,
        text: String,
        params: Map<String, String>,
    ): ConnectorOutput = ConnectorOutput.Success(
        text = text,
        data = mapOf("tool" to tool.name, "params_count" to params.size.toString()),
    )
}
