package com.airi.assistant.agent.request

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.CapabilityIntentDetector.Capability
import com.airi.assistant.ai.CodingTaskDetector
import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.ExecutionRequest.ToolChoice

/** The single record of one admission decision: what was understood, offered, withheld and why. */
data class RequestPlan(
    val kind: Kind,
    val queryType: QueryType,
    val confidence: Confidence,
    val capabilities: Set<Capability>,
    val exposedTools: List<String>,
    /** Every tool that was candidate but not offered, with the reason. */
    val blockedTools: Map<String, BlockReason>,
    /** Tools the request cannot be fulfilled without. */
    val requiredTools: Set<String>,
    val toolChoice: ToolChoice,
    /** Non-null when the request needs tools that cannot be offered; the UI must say so. */
    val unfulfillable: Unfulfillable?,
    val reason: String,
) {
    enum class Kind { CHAT, ACTION, CODE_TASK }
    enum class Confidence { LOW, MEDIUM, HIGH }
    enum class BlockReason { CONVERSATION_ONLY, NOT_RELEVANT, MODEL_HAS_NO_TOOLS, NOT_AVAILABLE }
    enum class Unfulfillable { MODEL_HAS_NO_TOOLS, REQUIRED_TOOL_MISSING }
}

/**
 * Pure planner (closure plan, phase 1.1). ChatViewModel and AgentLoop are meant to call this one
 * function so the two never disagree about intent, exposed tools or tool choice. It reads only its
 * arguments, so every decision is unit-testable and can be recorded with its values.
 */
object AgentRequestPlanner {
    private val toolsFor: Map<Capability, Set<String>> = mapOf(
        Capability.CODE_TASK to setOf("write_file", "check_syntax"),
        Capability.FILE_WRITE to setOf("write_file"),
        Capability.TERMINAL_EXECUTE to setOf("terminal_execute"),
        Capability.WEB_SEARCH to setOf("web_search"),
        Capability.CURRENT_TIME to setOf("current_time"),
        Capability.MEMORY_READ to setOf("memory_recall"),
    )

    /** Capabilities whose request is not satisfied by a text answer. */
    private val requiredFor: Map<Capability, String> = mapOf(
        Capability.CODE_TASK to "write_file",
        Capability.FILE_WRITE to "write_file",
        Capability.TERMINAL_EXECUTE to "terminal_execute",
    )

    /**
     * Connector tools are generated from the live registry, so they cannot be kept in a
     * static Capability -> tool-name map.  The canonical bridge names them as
     * `connector_<runtime-connector-id>_<action>`; use the detector's explicit connector
     * targets to retain only the requested connector family.
     */
    private fun isRequestedConnectorTool(
        toolName: String,
        connectorIds: Set<String>,
    ): Boolean {
        if (!toolName.startsWith("connector_")) return false
        if ("*" in connectorIds) return true
        return connectorIds.any { id -> toolName.startsWith("connector_${id.lowercase()}_") }
    }

    fun plan(
        text: String,
        support: ModelToolSupport,
        availableTools: Set<String>,
    ): RequestPlan {
        val coding = CodingTaskDetector.detect(text)
        val runtimeIntent = CapabilityIntentDetector.detect(text)
        val capabilities = buildSet {
            addAll(runtimeIntent.capabilities)
            if (coding != null) add(Capability.CODE_TASK)
        }
        val kind = when {
            Capability.CODE_TASK in capabilities -> RequestPlan.Kind.CODE_TASK
            capabilities.isNotEmpty() -> RequestPlan.Kind.ACTION
            else -> RequestPlan.Kind.CHAT
        }
        val queryType = when (kind) {
            RequestPlan.Kind.CODE_TASK, RequestPlan.Kind.ACTION -> QueryType.ACTION
            RequestPlan.Kind.CHAT -> com.airi.assistant.ai.QueryClassifier.classifyQuery(text)
        }
        val confidence = when {
            coding != null && coding.languages.isNotEmpty() -> RequestPlan.Confidence.HIGH
            capabilities.isNotEmpty() -> RequestPlan.Confidence.MEDIUM
            else -> RequestPlan.Confidence.LOW
        }

        val staticWanted = capabilities.flatMap { toolsFor[it].orEmpty() }.toSet()
        val connectorRequested = capabilities.contains(Capability.CONNECTOR_READ) ||
            capabilities.contains(Capability.CONNECTOR_WRITE)
        val wanted = buildSet {
            addAll(staticWanted)
            if (connectorRequested) {
                addAll(availableTools.filter { isRequestedConnectorTool(it, runtimeIntent.connectorIds) })
            }
        }
        val required = buildSet {
            addAll(capabilities.mapNotNull { requiredFor[it] })
            // A connector action is fulfilled by at least one matching live connector tool.
            // Keep the concrete names out of requiredTools: a request may legitimately have
            // several actions (for example Gmail list + read), while the absence of all matching
            // tools must still be reported as an admission failure below.
        }
        val blocked = linkedMapOf<String, RequestPlan.BlockReason>()
        val exposed = mutableListOf<String>()

        for (tool in availableTools.sorted()) {
            when {
                !support.canCallTools -> blocked[tool] = RequestPlan.BlockReason.MODEL_HAS_NO_TOOLS
                kind == RequestPlan.Kind.CHAT -> blocked[tool] = RequestPlan.BlockReason.CONVERSATION_ONLY
                tool !in wanted -> blocked[tool] = RequestPlan.BlockReason.NOT_RELEVANT
                else -> exposed += tool
            }
        }
        for (tool in required) if (tool !in availableTools) blocked[tool] = RequestPlan.BlockReason.NOT_AVAILABLE

        val matchingConnectorTools = if (connectorRequested) {
            availableTools.filter { isRequestedConnectorTool(it, runtimeIntent.connectorIds) }
        } else emptyList()
        val unfulfillable = when {
            required.isNotEmpty() && !support.canCallTools -> RequestPlan.Unfulfillable.MODEL_HAS_NO_TOOLS
            required.any { it !in availableTools } -> RequestPlan.Unfulfillable.REQUIRED_TOOL_MISSING
            connectorRequested && matchingConnectorTools.isEmpty() -> RequestPlan.Unfulfillable.REQUIRED_TOOL_MISSING
            else -> null
        }
        // Forcing is only possible where the provider carries tool declarations. Text-protocol models
        // are covered by the action-completion guard instead.
        val choice = when {
            exposed.isEmpty() -> ToolChoice.NONE
            required.isNotEmpty() && support == ModelToolSupport.NATIVE -> ToolChoice.REQUIRED
            else -> ToolChoice.AUTO
        }
        return RequestPlan(
            kind = kind,
            queryType = queryType,
            confidence = confidence,
            capabilities = capabilities,
            exposedTools = exposed,
            blockedTools = blocked,
            requiredTools = required,
            toolChoice = choice,
            unfulfillable = unfulfillable,
            reason = "kind=$kind capabilities=${capabilities.sorted()} support=$support exposed=${exposed.size} blocked=${blocked.size}",
        )
    }
}
