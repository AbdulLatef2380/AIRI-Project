package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector

/**
 * Single admission point between registered capabilities and AgentLoop.
 * Product discovery is supplied separately by RuntimeCapabilityInventory; this
 * catalog contains only ready request schemas that the model may attempt.
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

    data class StatusOverride(
        val readiness: RuntimeToolContract.Readiness,
        val reason: String,
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
        statusOverrides: Map<String, StatusOverride> = emptyMap(),
        requestText: String = "",
    ): Result {
        val effectiveOverrides = AgentLoopSideEffectPolicy.blockedToolOverrides() + statusOverrides
        val builtinContracts = builtins.map { schema ->
            effectiveOverrides[schema.name]?.let { override ->
                RuntimeToolContract(
                    schema = schema,
                    readiness = override.readiness,
                    source = RuntimeToolContract.Source.BUILTIN,
                    reason = override.reason,
                )
            } ?: RuntimeToolContract.builtin(schema)
        }
        val connectorContracts = connectors.map { contract ->
            effectiveOverrides[contract.schema.name]?.let { override ->
                contract.copy(readiness = override.readiness, reason = override.reason)
            } ?: contract
        }
        val candidates = builtinContracts + skills.map { RuntimeToolContract.skill(it) } + connectorContracts
        val requestedConnectorIds = intent.connectorIds
        val exposed = mutableListOf<RuntimeToolContract>()
        val filtered = mutableListOf<FilteredTool>()

        candidates.forEach { contract ->
            val connectorRequested = "*" in requestedConnectorIds ||
                contract.capabilityId?.let { it in requestedConnectorIds } == true
            when {
                contract.source == RuntimeToolContract.Source.CONNECTOR &&
                    contract.readiness == RuntimeToolContract.Readiness.AVAILABLE && connectorRequested ->
                    exposed += contract
                contract.source == RuntimeToolContract.Source.CONNECTOR -> filtered += FilteredTool(
                    contract.schema.name,
                    if (connectorRequested) Reason.NOT_READY else Reason.CONNECTOR_NOT_REQUESTED,
                )
                contract.readiness == RuntimeToolContract.Readiness.AVAILABLE -> exposed += contract
                else -> filtered += FilteredTool(contract.schema.name, Reason.NOT_READY)
            }
        }

        val targetedBuiltins = buildSet {
            if (intent.requires(CapabilityIntentDetector.Capability.CURRENT_TIME)) add("current_time")
            if (intent.requires(CapabilityIntentDetector.Capability.MEMORY_READ)) add("memory_recall")
            if (intent.requires(CapabilityIntentDetector.Capability.WEB_SEARCH)) {
                add("web_search")
                add("fetch_url")
            }
            if (intent.requires(CapabilityIntentDetector.Capability.DEVICE_STATE) &&
                (requestText.contains("screen", ignoreCase = true) || requestText.contains("الشاشة"))) add("read_screen")
        }
        val normalizedRequest = CapabilityIntentDetector.normalize(requestText)
        val ordered = exposed.distinctBy { it.schema.name }.sortedWith(
            compareBy<RuntimeToolContract> { contract ->
                when {
                    contract.source == RuntimeToolContract.Source.CONNECTOR -> 0
                    contract.schema.name in targetedBuiltins -> 1
                    contract.source == RuntimeToolContract.Source.SKILL &&
                        schemaMatchesRequest(contract.schema, normalizedRequest) -> 2
                    contract.source == RuntimeToolContract.Source.SKILL -> 3
                    contract.source == RuntimeToolContract.Source.BUILTIN -> 4
                    else -> 5
                }
            }.thenBy { it.schema.name }
        )

        return Result(
            candidates = candidates,
            exposed = ordered,
            filtered = filtered,
        )
    }

    private fun schemaMatchesRequest(schema: ToolSchema, normalizedRequest: String): Boolean {
        if (normalizedRequest.isBlank()) return false
        val tokens = (schema.name + " " + schema.description)
            .lowercase()
            .split(Regex("[^a-z0-9\\u0600-\\u06ff]+"))
            .filter { it.length >= 4 }
        return tokens.any { token -> normalizedRequest.contains(token) }
    }

    private fun RuntimeToolContract.promptSchema(): ToolSchema {
        if (readiness == RuntimeToolContract.Readiness.AVAILABLE) return schema
        val status = readiness.name.lowercase()
        val detail = reason?.takeIf { it.isNotBlank() } ?: "Capability is not ready"
        return schema.copy(description = "${schema.description} [READINESS=$status: $detail]")
    }
}
