package com.airi.assistant.core

import java.security.MessageDigest
import java.util.UUID

/**
 * PHASE 0 diagnostic trace only. It records routing evidence without changing
 * execution behavior and without persisting raw user/provider payloads.
 */
class UniversalRuntimeTraceRecorder(
    private val sink: (UniversalRuntimeTraceEvent) -> Unit = {}
) {
    private val lock = Any()
    private val _events = mutableListOf<UniversalRuntimeTraceEvent>()
    private var sequence = 0L
    private var currentTraceId: String? = null

    val events: List<UniversalRuntimeTraceEvent>
        get() = synchronized(lock) { _events.toList() }

    fun begin(
        executionId: String,
        sessionId: String,
        modelId: String,
        providerId: String,
        executionMode: String,
        input: String,
        tools: List<com.airi.assistant.agent.loop.tool.ToolSchema>,
        additionalCapabilities: List<CapabilitySnapshotEntry> = emptyList(),
    ): String {
        val traceId = UUID.randomUUID().toString()
        synchronized(lock) {
            currentTraceId = traceId
            sequence = 0L
            _events.clear()
        }
        record(
            traceId = traceId,
            executionId = executionId,
            sessionId = sessionId,
            eventType = UniversalTraceEventType.REQUEST_RECEIVED,
            component = "AgentLoop",
            outcome = "accepted",
            attributes = mapOf(
                "inputLength" to input.length.toString(),
                "inputSha256" to RuntimeTraceRedaction.sha256(input),
                "intent" to "agent_loop",
            )
        )
        record(
            traceId = traceId,
            executionId = executionId,
            sessionId = sessionId,
            eventType = UniversalTraceEventType.MODEL_CONTEXT_RESOLVED,
            component = "AgentLoop",
            outcome = "resolved",
            attributes = mapOf(
                "provider" to RuntimeTraceRedaction.token(providerId),
                "model" to RuntimeTraceRedaction.token(modelId),
                "executionMode" to executionMode,
            )
        )
        val snapshot = additionalCapabilities + tools.map { tool ->
            CapabilitySnapshotEntry(
                id = tool.name,
                kind = "tool",
                exists = true,
                registered = true,
                connected = null,
                authenticated = null,
                healthy = null,
                permitted = null,
                executable = null,
                modelCompatible = null,
                exposed = true,
            )
        }
        record(
            traceId = traceId,
            executionId = executionId,
            sessionId = sessionId,
            eventType = UniversalTraceEventType.CAPABILITY_SNAPSHOT_RESOLVED,
            component = "AgentLoop",
            outcome = "resolved",
            capabilities = snapshot,
            attributes = mapOf("toolCount" to tools.size.toString())
        )
        record(
            traceId = traceId,
            executionId = executionId,
            sessionId = sessionId,
            eventType = UniversalTraceEventType.CAPABILITY_FILTERED,
            component = "UniversalCapabilityDiscovery",
            outcome = "classified",
            attributes = UniversalCapabilityDiscovery.outcomeCounts(snapshot)
                .mapKeys { (outcome, _) -> "outcome.${outcome.name}" }
                .mapValues { (_, count) -> count.toString() }
        )
        record(
            traceId = traceId,
            executionId = executionId,
            sessionId = sessionId,
            eventType = UniversalTraceEventType.TOOLS_EXPOSED,
            component = "AgentLoop",
            outcome = if (tools.isEmpty()) "empty" else "exposed",
            toolNames = tools.map { it.name },
            attributes = tools.associate { tool ->
                "schema.${tool.name}.sha256" to RuntimeTraceRedaction.schemaHash(tool)
            } + tools.associate { tool ->
                "schema.${tool.name}.size" to RuntimeTraceRedaction.schemaSize(tool).toString()
            }
        )
        return traceId
    }

    fun modelResponse(
        executionId: String,
        sessionId: String,
        response: String,
        containsToolCallCandidate: Boolean,
        parserInputClassification: String,
    ) = record(
        eventType = UniversalTraceEventType.MODEL_RESPONSE_RECEIVED,
        executionId = executionId,
        sessionId = sessionId,
        component = "AgentLoop",
        outcome = "received",
        attributes = mapOf(
            "length" to response.length.toString(),
            "sha256" to RuntimeTraceRedaction.sha256(response),
            "redactedPreview" to RuntimeTraceRedaction.preview(response),
            "containsToolCallCandidate" to containsToolCallCandidate.toString(),
            "parserInputClassification" to parserInputClassification,
        )
    )

    fun parser(
        executionId: String,
        sessionId: String,
        parsed: Boolean,
        toolName: String? = null,
        reason: String? = null,
    ) = record(
        eventType = UniversalTraceEventType.TOOL_PARSE_COMPLETED,
        executionId = executionId,
        sessionId = sessionId,
        component = "AgentLoop.parseToolCall",
        outcome = if (parsed) "parsed" else "not_parsed",
        toolNames = toolName?.let(::listOf).orEmpty(),
        attributes = buildMap {
            put("parser", "text_json_tool_call")
            reason?.let { put("reason", RuntimeTraceRedaction.safeReason(it)) }
        }
    )

    fun selected(executionId: String, sessionId: String, toolName: String, valid: Boolean) = record(
        eventType = UniversalTraceEventType.TOOL_SELECTED,
        executionId = executionId,
        sessionId = sessionId,
        component = "AgentLoop",
        outcome = if (valid) "selected" else "rejected",
        toolNames = listOf(toolName),
        attributes = mapOf("schemaValidation" to if (valid) "passed" else "failed")
    )

    fun dispatched(executionId: String, sessionId: String, toolName: String, path: String) = record(
        eventType = UniversalTraceEventType.TOOL_DISPATCHED,
        executionId = executionId,
        sessionId = sessionId,
        component = "ToolDispatcher",
        outcome = "dispatched",
        toolNames = listOf(toolName),
        attributes = mapOf("executionPath" to path)
    )

    fun pathResolved(
        executionId: String,
        sessionId: String,
        route: UniversalExecutionPath,
    ) = record(
        eventType = UniversalTraceEventType.EXECUTION_PATH_RESOLVED,
        executionId = executionId,
        sessionId = sessionId,
        component = "UniversalExecutionPathResolver",
        outcome = route.classification.name.lowercase(),
        toolNames = listOf(route.toolName),
        attributes = buildMap {
            put("expectedPath", route.expectedPath.name)
            put("actualPath", route.actualPath.name)
            route.connectorId?.let { put("connectorId", it) }
        }
    )

    fun executionCompleted(
        executionId: String,
        sessionId: String,
        toolName: String,
        success: Boolean,
        durationMs: Long,
        resultLength: Int,
        errorCategory: String? = null,
    ) = record(
        eventType = UniversalTraceEventType.CONNECTOR_EXECUTION_COMPLETED,
        executionId = executionId,
        sessionId = sessionId,
        component = "ToolDispatcher",
        outcome = if (success) "success" else "failure",
        toolNames = listOf(toolName),
        attributes = buildMap {
            put("durationMs", durationMs.coerceAtLeast(0L).toString())
            put("resultLength", resultLength.toString())
            errorCategory?.let { put("errorCategory", RuntimeTraceRedaction.safeReason(it)) }
        }
    )

    fun resultReturned(executionId: String, sessionId: String, toolName: String) = record(
        eventType = UniversalTraceEventType.RESULT_RETURNED,
        executionId = executionId,
        sessionId = sessionId,
        component = "AgentLoop",
        outcome = "returned",
        toolNames = listOf(toolName)
    )

    fun continuation(executionId: String, sessionId: String, continued: Boolean, terminalState: String) = record(
        eventType = UniversalTraceEventType.MODEL_CONTINUATION_COMPLETED,
        executionId = executionId,
        sessionId = sessionId,
        component = "AgentLoop",
        outcome = if (continued) "continued" else "terminal",
        attributes = mapOf("terminalState" to terminalState)
    )

    fun uiProjection(executionId: String, sessionId: String, outcome: String) = record(
        eventType = UniversalTraceEventType.UI_PROJECTION_COMPLETED,
        executionId = executionId,
        sessionId = sessionId,
        component = "ChatViewModel",
        outcome = RuntimeTraceRedaction.safeReason(outcome)
    )

    private fun record(
        traceId: String? = null,
        executionId: String,
        sessionId: String,
        eventType: UniversalTraceEventType,
        component: String,
        outcome: String,
        toolNames: List<String> = emptyList(),
        capabilities: List<CapabilitySnapshotEntry> = emptyList(),
        attributes: Map<String, String> = emptyMap(),
    ): UniversalRuntimeTraceEvent {
        val event = synchronized(lock) {
            val resolvedTraceId = traceId ?: currentTraceId ?: "unbound"
            UniversalRuntimeTraceEvent(
                traceId = resolvedTraceId,
                executionId = executionId,
                sessionId = sessionId.takeIf { it.isNotBlank() },
                sequence = ++sequence,
                eventType = eventType,
                component = component,
                outcome = outcome,
                toolNames = toolNames.map(RuntimeTraceRedaction::token),
                capabilities = capabilities,
                attributes = attributes.mapValues { (_, value) -> RuntimeTraceRedaction.attribute(value) },
            ).also { _events += it }
        }
        sink(event)
        return event
    }
}

enum class UniversalTraceEventType {
    REQUEST_RECEIVED,
    MODEL_CONTEXT_RESOLVED,
    CAPABILITY_SNAPSHOT_RESOLVED,
    CAPABILITY_FILTERED,
    TOOLS_EXPOSED,
    MODEL_REQUEST_BUILT,
    MODEL_RESPONSE_RECEIVED,
    TOOL_PARSE_COMPLETED,
    TOOL_SELECTED,
    TOOL_DISPATCHED,
    EXECUTION_PATH_RESOLVED,
    CONNECTOR_EXECUTION_COMPLETED,
    RESULT_RETURNED,
    MODEL_CONTINUATION_COMPLETED,
    UI_PROJECTION_COMPLETED,
}

data class CapabilitySnapshotEntry(
    val id: String,
    val kind: String,
    val exists: Boolean?,
    val registered: Boolean?,
    val connected: Boolean?,
    val authenticated: Boolean?,
    val healthy: Boolean?,
    val permitted: Boolean?,
    val executable: Boolean?,
    val modelCompatible: Boolean?,
    val exposed: Boolean?,
    val selected: Boolean? = null,
    val executed: Boolean? = null,
    val resultReturned: Boolean? = null,
)

data class UniversalRuntimeTraceEvent(
    val traceId: String,
    val executionId: String,
    val sessionId: String?,
    val sequence: Long,
    val eventType: UniversalTraceEventType,
    val component: String,
    val outcome: String,
    val toolNames: List<String> = emptyList(),
    val capabilities: List<CapabilitySnapshotEntry> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)

object RuntimeTraceRedaction {
    private const val MAX_PREVIEW = 160
    private val secretPattern = Regex("(?i)(?:bearer\\s+[^,;\\s]+|(?:token|api[_-]?key|secret|password|authorization)\\s*[:=]\\s*[^,;\\s]+)")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun preview(value: String): String = attribute(value)
        .replace(secretPattern, "[REDACTED]")
        .replace(emailPattern, "[EMAIL]")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_PREVIEW)

    fun safeReason(value: String): String = preview(value).ifBlank { "unspecified" }

    fun token(value: String): String = value.trim().take(80).ifBlank { "unknown" }

    fun attribute(value: String): String = value
        .replace(secretPattern, "[REDACTED]")
        .replace(emailPattern, "[EMAIL]")
        .take(MAX_PREVIEW)

    fun schemaSize(tool: com.airi.assistant.agent.loop.tool.ToolSchema): Int =
        schemaText(tool).length

    fun schemaHash(tool: com.airi.assistant.agent.loop.tool.ToolSchema): String =
        sha256(schemaText(tool))

    private fun schemaText(tool: com.airi.assistant.agent.loop.tool.ToolSchema): String = buildString {
        append(tool.name).append('|').append(tool.category.name).append('|').append(tool.dangerous)
        tool.parameters.toSortedMap().forEach { (name, parameter) ->
            append('|').append(name).append(':').append(parameter.type).append(':').append(parameter.required)
        }
    }
}
