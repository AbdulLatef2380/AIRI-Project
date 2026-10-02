package com.airi.assistant.connector

import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Canonical bridge between live ConnectorRegistry entries and AgentLoop.
 * Catalog-only, disconnected, unhealthy, and write actions are never exposed.
 */
class ConnectorToolBridge(
    private val registry: ConnectorRegistry,
    private val runtime: ConnectorRuntimeManager,
) {
    private data class Binding(
        val connectorId: String,
        val action: ConnectorAgentAction,
    )

    fun asToolSchemas(): List<ToolSchema> = bindings(onlyExecutable = true)
        .map { (toolName, binding) -> binding.toSchema(toolName) }
        .sortedBy { it.name }

    /** Keep known bindings resolvable after a disconnect so runtime returns a
     * stable not_connected result instead of misclassifying the call as unknown. */
    fun handles(toolName: String): Boolean = bindings(onlyExecutable = false).containsKey(toolName)

    suspend fun invoke(toolName: String, args: Map<String, String>): ConnectorOutput {
        val binding = bindings(onlyExecutable = false)[toolName]
            ?: return ConnectorOutput.Failure("unknown_tool", "Unknown connector tool: $toolName")
        val text = args["text"] ?: args["query"].orEmpty()
        return runtime.execute(
            connectorId = binding.connectorId,
            input = ConnectorInput(
                action = binding.action.id,
                text = text,
                params = args,
            )
        )
    }

    private fun bindings(onlyExecutable: Boolean): Map<String, Binding> = buildMap {
        registry.all().forEach { connector ->
            val state = connector.state().value
            if (onlyExecutable && (!state.connected || !state.healthy)) return@forEach
            connector.agentActions()
                .filter { it.id.isNotBlank() && it.permission == ConnectorPermissionLevel.READ }
                .forEach { action ->
                    val toolName = toolName(connector.id, action.id)
                    put(toolName, Binding(connector.id, action))
                }
        }
    }

    private fun toolName(connectorId: String, actionId: String): String =
        "connector_${connectorId}_${actionId}"
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')

    private fun Binding.toSchema(toolName: String): ToolSchema {
        return ToolSchema(
            name = toolName,
            description = "[$connectorId] ${action.description}",
            parameters = action.parameters.mapValues { (_, parameter) ->
                ToolSchema.Param(parameter.type, parameter.description, parameter.required)
            },
            category = ToolSchema.Category.EXTERNAL,
        )
    }
}
