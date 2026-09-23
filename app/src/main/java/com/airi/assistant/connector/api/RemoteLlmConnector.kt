package com.airi.assistant.connector.api

import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remote LLM connector with provider fallback.
 *
 * Holds an ordered chain of providers (e.g. OpenAI → Anthropic → Gemini).
 * On each `chat` call it tries providers in order and returns the first
 * success. Provider-level network/rate-limit failures are marked
 * retryable so [com.airi.assistant.connector.AgentRouter] can also fall
 * back to other API connectors registered alongside this one.
 *
 * This connector intentionally does NOT bundle an HTTP client itself —
 * each [Provider] owns its own transport. That keeps the connector
 * test-friendly: in unit tests the providers are pure functions.
 */
class RemoteLlmConnector(
    override val id: String = "remote_llm",
    override val name: String = "Remote LLM",
    override val description: String = "Cloud language models with automatic fallback.",
    private val providers: List<Provider>,
) : Connector {

    override val type: ConnectorType = ConnectorType.API

    private val _state = MutableStateFlow(
        ConnectorState(
            connected = providers.any { it.isConfigured() },
            healthy   = providers.any { it.isConfigured() },
            statusLine = providersSummary(),
        )
    )

    override fun meta(): ConnectorMeta = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        tags = providers.map { it.label },
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val anyReady = providers.any { it.isConfigured() }
        val s = ConnectorState(
            connected = anyReady,
            healthy   = anyReady,
            statusLine = providersSummary(),
            lastUpdatedMs = System.currentTimeMillis(),
            errorMessage = if (anyReady) null else "No provider has credentials configured",
        )
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(
            connected = false,
            healthy = false,
            statusLine = "Disconnected",
            lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        if (!_state.value.connected) {
            return ConnectorOutput.Failure(
                code = "not_connected",
                message = "Remote LLM connector is disconnected",
                retryable = false,
            )
        }
        if (input.action !in ACCEPTED_ACTIONS) {
            return ConnectorOutput.Failure(
                code = "unknown_action",
                message = "RemoteLlmConnector does not handle action '${input.action}'",
            )
        }
        if (input.text.isBlank()) {
            return ConnectorOutput.Failure(
                code = "bad_input",
                message = "Empty prompt",
            )
        }

        val started = System.currentTimeMillis()
        var lastFailure: ConnectorOutput.Failure? = null

        for (provider in providers) {
            if (!provider.isConfigured()) continue
            try {
                val text = provider.complete(input.text, input.params)
                return ConnectorOutput.Success(
                    text = text,
                    data = mapOf("provider" to provider.label),
                    durationMs = System.currentTimeMillis() - started,
                )
            } catch (e: CancellationException) {
                // Never convert user/job cancellation into a provider failure;
                // doing so would incorrectly advance the fallback chain.
                throw e
            } catch (e: Throwable) {
                lastFailure = ConnectorOutput.Failure(
                    code = "provider_error",
                    message = "${provider.label}: ${e.message ?: e.javaClass.simpleName}",
                    retryable = true,
                )
            }
        }

        return lastFailure ?: ConnectorOutput.Failure(
            code = "not_connected",
            message = "No configured provider available",
            retryable = false,
        )
    }

    private fun providersSummary(): String {
        val ready = providers.count { it.isConfigured() }
        return "$ready / ${providers.size} provider(s) configured"
    }

    /**
     * Pluggable provider contract. Implementations live next to their
     * transport code so the connector stays transport-agnostic.
     */
    interface Provider {
        /** Short label, e.g. "openai", "anthropic". */
        val label: String

        /** Cheap check — true if API key / endpoint is present. MUST NOT
         *  perform I/O. */
        fun isConfigured(): Boolean

        /** Single-shot completion. May throw on transport errors;
         *  the connector translates throws into retryable Failures. */
        suspend fun complete(prompt: String, params: Map<String, String>): String
    }

    companion object {
        private val ACCEPTED_ACTIONS = setOf(
            "chat", "analyze_code", "debug", "summarize",
        )
    }
}
