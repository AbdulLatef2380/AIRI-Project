package com.airi.assistant.connector

import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Canonical bridge between live ConnectorRegistry entries and AgentLoop.
 * Only registered, connected, healthy, and user-granted actions are exposed by default.
 * Actions requiring separate confirmation stay hidden until a typed approval path exists.
 */
class ConnectorToolBridge(
    private val registry: ConnectorRegistry,
    private val runtime: ConnectorRuntimeManager,
    private val accessProfiles: ConnectorAccessProfileStore = InMemoryConnectorAccessProfileStore(),
) {
    private data class Binding(
        val connectorId: String,
        val action: ConnectorAgentAction,
    )

    fun asToolSchemas(): List<ToolSchema> = asToolSchemas(includeUnavailable = false)

    /**
     * Expose granted actions even when disconnected/unhealthy when the
     * caller needs a truthful readiness result. Invocation remains guarded by
     * ConnectorRuntimeManager and returns not_connected/unhealthy/auth errors.
     */
    fun asToolSchemas(includeUnavailable: Boolean): List<ToolSchema> = bindings(
        onlyExecutable = !includeUnavailable, onlyGranted = true
    )
        .map { (toolName, binding) -> binding.toSchema(toolName) }
        .sortedBy { it.name }

    /** Keep known bindings resolvable after a disconnect so runtime returns a
     * stable not_connected result instead of misclassifying the call as unknown. */
    fun handles(toolName: String): Boolean = bindings(onlyExecutable = false, onlyGranted = false).containsKey(toolName)

    /** Runtime connector identity for request-intent filtering; never inferred from action-name tokens. */
    fun connectorIdForTool(toolName: String): String? =
        bindings(onlyExecutable = false, onlyGranted = false)[toolName]?.connectorId

    suspend fun invoke(toolName: String, args: Map<String, String>): ConnectorOutput {
        val binding = bindings(onlyExecutable = false, onlyGranted = false)[toolName]
            ?: return ConnectorOutput.Failure("unknown_tool", "Unknown connector tool: $toolName")
        when (ConnectorAccessPolicy.evaluate(
            accessProfiles.get(binding.action.surfaceId ?: binding.connectorId), binding.action
        )) {
            ConnectorAccessDecision.ALLOWED -> Unit
            ConnectorAccessDecision.NOT_GRANTED -> return ConnectorOutput.Failure(
                "permission_denied", "No matching user access profile is granted for this connector action"
            )
            ConnectorAccessDecision.CONFIRMATION_REQUIRED -> return ConnectorOutput.Failure(
                "approval_required", "This action requires a typed approval flow and was not executed"
            )
        }
        val text = if (binding.action.maxTextChars > 0) {
            args["text"] ?: args["prompt"] ?: args["query"].orEmpty()
        } else {
            ""
        }
        return runtime.execute(
            connectorId = binding.connectorId,
            input = ConnectorInput(
                action = binding.action.runtimeAction,
                text = text,
                params = args.filterKeys { it != "text" || it in binding.action.parameters } + binding.action.fixedParams,
                authorizationActionId = binding.action.id,
            )
        )
    }

    private fun bindings(onlyExecutable: Boolean, onlyGranted: Boolean): Map<String, Binding> = buildMap {
        registry.all().forEach { connector ->
            val state = connector.state().value
            if (onlyExecutable && (!state.connected || !state.healthy)) return@forEach
            connector.agentActions()
                .filter { it.id.isNotBlank() }
                .filter { action -> !onlyGranted ||
                    ConnectorAccessPolicy.evaluate(
                        accessProfiles.get(action.surfaceId ?: connector.id), action
                    ) == ConnectorAccessDecision.ALLOWED
                }
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
