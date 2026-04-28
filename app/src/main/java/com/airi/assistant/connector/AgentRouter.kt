package com.airi.assistant.connector

import com.airi.assistant.core.intent.IntentType

/**
 * AgentRouter — picks the right [Connector] for a given [IntentType] +
 * input, executes it, and falls back to the next-best connector on a
 * retryable failure.
 *
 * The router is intentionally dumb (no LLM-in-the-loop classifier here):
 * intent classification has already happened upstream in the agent stack.
 * The router's only job is to map (intent, type-bucket) → ordered list of
 * candidate connectors, then walk that list.
 */
class AgentRouter(
    private val registry: ConnectorRegistry,
) {
    /**
     * Route a classified intent through the registered connectors.
     *
     * Resolution order:
     *  1. If [preferConnectorId] is set and that connector is registered,
     *     try it first.
     *  2. Otherwise iterate over [candidatesFor] in declared priority.
     *
     * Returns the first non-failure result, or the last failure if every
     * candidate failed.
     */
    suspend fun route(
        intent: IntentType,
        text: String,
        params: Map<String, String> = emptyMap(),
        preferConnectorId: String? = null,
    ): RouteResult {
        val ordered = buildList {
            preferConnectorId?.let { id -> registry.get(id)?.let(::add) }
            for (c in candidatesFor(intent)) {
                if (c !in this) add(c)
            }
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
            val input = ConnectorInput(
                action = actionFor(intent),
                text   = text,
                params = params,
            )
            val out = connector.execute(input)
            attempts += Attempt(connector.id, out)

            when (out) {
                is ConnectorOutput.Success,
                is ConnectorOutput.Streaming -> {
                    return RouteResult(connector.id, out, attempts)
                }
                is ConnectorOutput.Failure -> {
                    lastFailure = out
                    if (!out.retryable) {
                        // Hard failure (auth, unknown_action) — don't burn
                        // through the fallback chain just to fail again.
                        return RouteResult(connector.id, out, attempts)
                    }
                    // Retryable: try the next candidate.
                }
            }
        }

        return RouteResult(
            connectorId = ordered.last().id,
            output = lastFailure ?: ConnectorOutput.Failure(
                code    = "exhausted",
                message = "All ${ordered.size} candidate connector(s) failed",
            ),
            attempts = attempts,
        )
    }

    /**
     * Candidate connectors for an intent, in priority order. Designed to
     * be deterministic so failures are reproducible.
     */
    private fun candidatesFor(intent: IntentType): List<Connector> {
        val bucket = tabFor(intent)
        return registry.byType(bucket)
    }

    /**
     * Map a high-level intent → which connector tab handles it. Keep this
     * in sync with the UI tabs in ConnectorsScreen.kt.
     */
    private fun tabFor(intent: IntentType): ConnectorType = when (intent) {
        IntentType.GENERAL,
        IntentType.CONVERSATION,
        IntentType.CODE_ANALYSIS,
        IntentType.DEBUG_ERROR,
        IntentType.SUMMARIZE       -> ConnectorType.API   // LLM workloads

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
        IntentType.SCROLL          -> ConnectorType.LOCAL

        IntentType.UNKNOWN         -> ConnectorType.API   // best-effort: fall back to LLM
    }

    /** Map intent → connector action string. Connectors document the
     *  set of actions they accept; unknown actions get `unknown_action`. */
    private fun actionFor(intent: IntentType): String = when (intent) {
        IntentType.CONVERSATION,
        IntentType.GENERAL,
        IntentType.UNKNOWN          -> "chat"
        IntentType.CODE_ANALYSIS    -> "analyze_code"
        IntentType.DEBUG_ERROR      -> "debug"
        IntentType.SUMMARIZE        -> "summarize"
        IntentType.SYSTEM_COMMAND   -> "system_exec"
        IntentType.APP_CONTROL      -> "open_app"
        IntentType.SCREEN_ANALYSIS  -> "screen_capture"
        IntentType.BATTERY_DIAGNOSIS -> "battery_status"
        IntentType.NAVIGATE         -> "navigate"
        IntentType.CLICK            -> "click"
        IntentType.CLICK_FIRST      -> "click_first"
        IntentType.CLICK_INDEX      -> "click_index"
        IntentType.TYPE             -> "type"
        IntentType.BACK             -> "back"
        IntentType.SCROLL           -> "scroll"
    }

    data class Attempt(val connectorId: String, val output: ConnectorOutput)

    data class RouteResult(
        val connectorId: String?,
        val output: ConnectorOutput,
        val attempts: List<Attempt>,
    )
}
