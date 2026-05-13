package com.airi.assistant.agent.tools

import android.util.Log
import com.airi.assistant.ai.tools.Tool
import com.airi.assistant.ai.tools.ToolRegistry
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * ToolResolver — unified tool discovery and execution facade.
 *
 * The agent pipeline can call tools by name without knowing whether they
 * are implemented as [Tool] (legacy tool model) or [Connector] (new model).
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
            // Legacy tools now expect a Map<String, String> and return ToolResult
            val params = call.params.toMutableMap()
            if (call.input.isNotBlank()) {
                params["input"] = call.input
            }
            val result = tool.execute(params)
            if (result.success) {
                Log.d(TAG, "TOOL_LEGACY_OK tool='${tool.name}'")
                ToolResult.Success(text = result.data)
            } else {
                Log.w(TAG, "TOOL_LEGACY_FAIL tool='${tool.name}' error=${result.error}")
                ToolResult.Failure(code = "legacy_error", message = result.error ?: "Unknown error")
            }
        }.getOrElse { e ->
            Log.w(TAG, "TOOL_LEGACY_FAIL tool='${tool.name}' error=${e.message}")
            ToolResult.Failure(code = "legacy_exception", message = e.message ?: "Unknown error")
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
            is ConnectorOutput.Streaming -> {
                // Bridge streaming to success by collecting the first chunk or joining
                // In a real tool resolver, we might want to handle streaming differently,
                // but for now we'll collect it to maintain the ToolResult.Success contract.
                val text = out.chunks.first() 
                ToolResult.Success(text = text)
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
     */
    fun availableToolNames(): List<String> {
        val legacy     = runCatching { toolRegistry.getAvailableTools().map { it.name } }.getOrDefault(emptyList())
        val connectors = runCatching {
            connectorRegistry.all().map { "${it.id}.<action>" }
        }.getOrDefault(emptyList())
        return (legacy + connectors).distinct()
    }
}
