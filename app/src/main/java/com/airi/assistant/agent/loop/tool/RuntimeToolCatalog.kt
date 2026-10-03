package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector

/**
 * Single admission point between registered capabilities and AgentLoop.
 *
 * The catalog does not execute anything. It only answers four questions for a
 * request: what was registered, what is ready, what may be exposed, and why a
 * capability was not exposed. This keeps local/cloud execution independent
 * from UI-specific assembly and makes the contract JVM-testable.
 */
object RuntimeToolCatalog {
    data class Result(
        val candidates: List<RuntimeToolContract>,
        val exposed: List<RuntimeToolContract>,
        val filtered: List<FilteredTool>,
    ) {
        val schemas: List<ToolSchema> get() = exposed.map { it.promptSchema() }
    }

    data class FilteredTool(
        val toolName: String,
        val reason: Reason,
    )

    enum class Reason {
        NOT_READY,
        CONNECTOR_NOT_REQUESTED,
    }

    fun assemble(
        builtins: List<ToolSchema>,
        skills: List<ToolSchema>,
        connectors: List<RuntimeToolContract>,
        intent: CapabilityIntentDetector.Intent,
    ): Result {
        val candidates = builtins.map(RuntimeToolContract::builtin) +
            skills.map(RuntimeToolContract::skill) + connectors

        val exposeUnavailableConnector = intent.requires(
            CapabilityIntentDetector.Capability.CONNECTOR_READ
        )
        val requestedConnectorIds = intent.connectorIds
        val exposed = mutableListOf<RuntimeToolContract>()
        val filtered = mutableListOf<FilteredTool>()
        candidates.forEach { contract ->
            val connectorRequested = requestedConnectorIds.isEmpty() ||
                "*" in requestedConnectorIds ||
                contract.capabilityId in requestedConnectorIds
            when {
                contract.readiness == RuntimeToolContract.Readiness.AVAILABLE -> exposed += contract
                contract.source == RuntimeToolContract.Source.CONNECTOR &&
                    exposeUnavailableConnector && connectorRequested -> exposed += contract
                contract.source == RuntimeToolContract.Source.CONNECTOR -> filtered += FilteredTool(
                    contract.schema.name,
                    Reason.CONNECTOR_NOT_REQUESTED,
                )
                else -> filtered += FilteredTool(contract.schema.name, Reason.NOT_READY)
            }
        }
        return Result(
            candidates = candidates,
            exposed = exposed.distinctBy { it.schema.name },
            filtered = filtered,
        )
    }

    private fun RuntimeToolContract.promptSchema(): ToolSchema {
        if (readiness == RuntimeToolContract.Readiness.AVAILABLE) return schema
        val status = readiness.name.lowercase()
        val detail = reason?.takeIf { it.isNotBlank() } ?: "Capability is not ready"
        return schema.copy(description = "${schema.description} [READINESS=$status: $detail]")
    }
}
