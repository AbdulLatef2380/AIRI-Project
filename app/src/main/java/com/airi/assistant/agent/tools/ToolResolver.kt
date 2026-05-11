package com.airi.assistant.agent.tools

import android.util.Log
import com.airi.assistant.ai.tools.Tool
import com.airi.assistant.ai.tools.ToolRegistry
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput

/**
 * ToolResolver — unified tool discovery and execution facade.
 *
 * The agent pipeline can call tools by name without knowing whether they
 * are implemented as [Tool] (legacy tool model) or [Connector] (new model).
 *
 * ── RESOLUTION ORDER ─────────────────────────────────────────────────────────
 *
 *  1. Exact match in [ToolRegistry] (legacy tools — GitHub, Telegram, etc.)
 *  2. Connector action bridge — tries to route via [ConnectorRegistry]
 *     matching on connector ID prefix (e.g. "weather.get_current")
 *  3. Returns [ToolResult.Unavailable] if no match is found.
 *
 * ── ACTION ENCODING ──────────────────────────────────────────────────────────
 *
 *   Tool calls from the LLM are encoded as:
 *     { "tool": "connector_id.action", "input": "...", "params": {...} }
 *
 *   E.g.:
 *     { "tool": "browser.fetch",       "input": "https://example.com" }
 *     { "tool": "calendar.list_events", "params": {"days": "7"}        }
 *     { "tool": "weather.get_current",  "params": {"location": "NYC"}  }
 *
 * ── SAFETY ───────────────────────────────────────────────────────────────────
 *
 *   All tool execution is wrapped in try/catch. ToolResult.Failure is returned
 *   rather than throwing, so an invalid tool call never crashes the agent loop.
 */
class ToolResolver(
    private val toolRegistry:      ToolRegistry,
    private val connectorRegistry: ConnectorRegistry,
) {

    private val TAG = "ToolResolver"

    // ── Data model ────────────────────────────────────────────────────────────

    sealed class ToolResult {
        data class Success(val text: String, val data: Map<String, String> = emptyMap()) : ToolResult()
        data class Failure(val code: String, val message: String, val retryable: Boolean = false) : ToolResult()
        data class Unavailable(val toolName: String) : ToolResult()
    }

    data class ToolCall(
        val toolName: String,
        val input:    String                = "",
        val params:   Map<String, String>   = emptyMap(),
    )

    // ── Resolution + execution ────────────────────────────────────────────────

    suspend fun execute(call: ToolCall): ToolResult {
        Log.d(TAG, "Resolving tool='${call.toolName}' input='${call.input.take(60)}'")

        // 1. Try legacy ToolRegistry (exact name match)
        val legacyTool = runCatching { toolRegistry.getAvailableTools()
            .firstOrNull { it.name.equals(call.toolName, ignoreCase = true) }
        }.getOrNull()

        if (legacyTool != null) {
            return executeLegacy(legacyTool, call)
        }

        // 2. Try connector route (format: "connector_id.action" or just "connector_id")
        val (connectorId, action) = parseConnectorRoute(call.toolName)
        if (connectorId != null) {
            return executeConnector(connectorId, action, call)
        }

        // 3. Try partial name match in connectors
        val allConnectors = runCatching { connectorRegistry.all() }.getOrDefault(emptyList())
        val fuzzyMatch = allConnectors.firstOrNull { c ->
            c.id.contains(call.toolName, ignoreCase = true) ||
            call.toolName.contains(c.id, ignoreCase = true)
        }
        if (fuzzyMatch != null) {
            return executeConnector(fuzzyMatch.id, "execute", call)
        }

        Log.w(TAG, "TOOL_NOT_FOUND tool='${call.toolName}'")
        return ToolResult.Unavailable(call.toolName)
    }

    private suspend fun executeLegacy(tool: Tool, call: ToolCall): ToolResult {
        return runCatching {
            val result = tool.execute(call.input, call.params)
            Log.d(TAG, "TOOL_LEGACY_OK tool='${tool.name}' result='${result.take(80)}'")
            ToolResult.Success(text = result)
        }.getOrElse { e ->
            Log.w(TAG, "TOOL_LEGACY_FAIL tool='${tool.name}' error=${e.message}")
            ToolResult.Failure(code = "legacy_error", message = e.message ?: "Unknown error", retryable = false)
        }
    }

    private suspend fun executeConnector(
        connectorId: String,
        action:      String,
        call:        ToolCall,
    ): ToolResult {
        val connector = runCatching { connectorRegistry.get(connectorId) }.getOrNull()
            ?: return ToolResult.Failure(
                code      = "connector_not_found",
                message   = "No connector with id '$connectorId'",
                retryable = false,
            )

        val input = ConnectorInput(
            action = action,
            text   = call.input,
            params = call.params,
        )

        return when (val out = runCatching { connector.execute(input) }.getOrElse { e ->
            ConnectorOutput.Failure(code = "connector_exception", message = e.message ?: "exception")
        }) {
            is ConnectorOutput.Success -> {
                Log.d(TAG, "TOOL_CONNECTOR_OK connector=$connectorId action=$action")
                ToolResult.Success(text = out.text, data = out.data)
            }
            is ConnectorOutput.Failure -> {
                Log.w(TAG, "TOOL_CONNECTOR_FAIL connector=$connectorId code=${out.code}")
                ToolResult.Failure(code = out.code, message = out.message, retryable = out.retryable)
            }
        }
    }

    private fun parseConnectorRoute(toolName: String): Pair<String?, String> {
        val dot = toolName.indexOf('.')
        return if (dot > 0) {
            toolName.substring(0, dot) to toolName.substring(dot + 1)
        } else {
            null to "execute"
        }
    }

    /**
     * List all available tool names across both [ToolRegistry] and [ConnectorRegistry].
     * Used by the LLM prompt builder to generate the tool list.
     */
    fun availableToolNames(): List<String> {
        val legacy     = runCatching { toolRegistry.getAvailableTools().map { it.name } }.getOrDefault(emptyList())
        val connectors = runCatching {
            connectorRegistry.all().map { "${it.id}.<action>" }
        }.getOrDefault(emptyList())
        return (legacy + connectors).distinct()
    }
}
