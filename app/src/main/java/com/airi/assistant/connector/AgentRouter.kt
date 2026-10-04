package com.airi.assistant.connector

import com.airi.assistant.core.intent.IntentType

/**
 * Compatibility router for classified intents. Every candidate is executed
 * through ConnectorRuntimeManager so lifecycle, declaration, profile, timeout,
 * and operation tracking rules are shared with the canonical tool bridge.
 */
class AgentRouter(
    private val registry: ConnectorRegistry,
    accessProfiles: ConnectorAccessProfileStore = InMemoryConnectorAccessProfileStore(),
    private val runtime: ConnectorRuntimeManager = ConnectorRuntimeManager(registry, accessProfiles),
) {
    suspend fun route(
        intent: IntentType,
        text: String,
        params: Map<String, String> = emptyMap(),
        preferConnectorId: String? = null,
    ): RouteResult {
        val ordered = buildList {
            preferConnectorId?.let { id -> registry.get(id)?.let(::add) }
            for (connector in candidatesFor(intent)) if (connector !in this) add(connector)
        }
        if (ordered.isEmpty()) {
            return RouteResult(
                connectorId = null,
                output = ConnectorOutput.Failure(
                    code = "no_connector",
                    message = "No connector is registered for intent $intent",
                    retryable = false,
                ),
                attempts = emptyList(),
            )
        }

        val attempts = mutableListOf<Attempt>()
        var lastFailure: ConnectorOutput.Failure? = null
        for (connector in ordered) {
            val action = actionFor(intent)
            val matches = connector.agentActions().filter { it.runtimeAction == action }
            if (matches.size != 1) {
                val failure = ConnectorOutput.Failure(
                    code = "undeclared_action",
                    message = "Connector '${connector.id}' does not declare exactly one authorized action for '$action'",
                    retryable = false,
                )
                attempts += Attempt(connector.id, failure)
                if (connector.id == preferConnectorId) return RouteResult(connector.id, failure, attempts)
                lastFailure = failure
                continue
            }
            val declaration = matches.single()
            val output = runtime.execute(
                connectorId = connector.id,
                input = ConnectorInput(
                    action = action,
                    text = text,
                    params = params,
                    authorizationActionId = declaration.id,
                ),
            )
            attempts += Attempt(connector.id, output)
            when (output) {
                is ConnectorOutput.Success,
                is ConnectorOutput.Streaming,
                is ConnectorOutput.ApprovalRequired -> return RouteResult(connector.id, output, attempts)
                is ConnectorOutput.Failure -> {
                    lastFailure = output
                    // A policy/lifecycle denial is authoritative; do not route
                    // around it by trying a different connector.
                    if (!output.retryable || output.code in NON_FALLBACK_CODES) {
                        return RouteResult(connector.id, output, attempts)
                    }
                }
            }
        }
        return RouteResult(
            connectorId = attempts.lastOrNull()?.connectorId,
            output = lastFailure ?: ConnectorOutput.Failure(
                code = "exhausted",
                message = "All ${ordered.size} candidate connector(s) failed",
            ),
            attempts = attempts,
        )
    }

    private fun candidatesFor(intent: IntentType): List<Connector> = registry.byType(tabFor(intent))

    private fun tabFor(intent: IntentType): ConnectorType = when (intent) {
        IntentType.GENERAL,
        IntentType.CONVERSATION,
        IntentType.CODE_ANALYSIS,
        IntentType.DEBUG_ERROR,
        IntentType.SUMMARIZE -> ConnectorType.API

        IntentType.SYSTEM_COMMAND,
        IntentType.BATTERY_DIAGNOSIS,
        IntentType.SCREEN_ANALYSIS -> ConnectorType.SYSTEM

        IntentType.APP_CONTROL,
        IntentType.NAVIGATE,
        IntentType.CLICK,
        IntentType.CLICK_FIRST,
        IntentType.CLICK_INDEX,
        IntentType.TYPE,
        IntentType.BACK,
        IntentType.SCROLL -> ConnectorType.LOCAL

        IntentType.UNKNOWN -> ConnectorType.API
    }

    private fun actionFor(intent: IntentType): String = when (intent) {
        IntentType.CONVERSATION,
        IntentType.GENERAL,
        IntentType.UNKNOWN -> "chat"
        IntentType.CODE_ANALYSIS -> "analyze_code"
        IntentType.DEBUG_ERROR -> "debug"
        IntentType.SUMMARIZE -> "summarize"
        IntentType.SYSTEM_COMMAND -> "system_exec"
        IntentType.APP_CONTROL -> "open_app"
        IntentType.SCREEN_ANALYSIS -> "screen_capture"
        IntentType.BATTERY_DIAGNOSIS -> "battery_status"
        IntentType.NAVIGATE -> "navigate"
        IntentType.CLICK -> "click"
        IntentType.CLICK_FIRST -> "click_first"
        IntentType.CLICK_INDEX -> "click_index"
        IntentType.TYPE -> "type"
        IntentType.BACK -> "back"
        IntentType.SCROLL -> "scroll"
    }

    data class Attempt(val connectorId: String, val output: ConnectorOutput)
    data class RouteResult(val connectorId: String?, val output: ConnectorOutput, val attempts: List<Attempt>)

    private companion object {
        val NON_FALLBACK_CODES = setOf("permission_denied", "approval_required", "not_connected", "undeclared_action")
    }
}
