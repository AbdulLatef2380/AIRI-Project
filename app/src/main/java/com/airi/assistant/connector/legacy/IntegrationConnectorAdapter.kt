package com.airi.assistant.connector.legacy

import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.integration.Integration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Adapter that exposes a legacy [Integration] (Github / Telegram /
 * Notion / N8n / …) as a [Connector].
 *
 * This is the migration bridge: existing integration code keeps working
 * untouched, and the new Connectors UI can list them under the APP tab
 * without rewriting the underlying integrations. Once each legacy
 * integration is reimplemented as a first-class connector, its adapter
 * registration is simply removed from
 * [com.airi.assistant.connector.ConnectorBootstrap].
 *
 * Behavior contract:
 *  - [connect] / [disconnect] delegate to the wrapped integration's
 *    synchronous methods (the legacy interface is sync).
 *  - [execute] is intentionally minimal — legacy integrations didn't
 *    expose an action API, so we only support `status` (returns the
 *    current connection state as text).
 */
class IntegrationConnectorAdapter(
    private val wrapped: Integration,
    /** Legacy integrations are all third-party app integrations
     *  (GitHub/Telegram/Notion/Google), so they all bucket under APP
     *  by default. Override only if a specific legacy integration is
     *  actually a different bucket. */
    override val type: ConnectorType = ConnectorType.APP,
) : Connector {

    override val id          = "legacy_${wrapped.id}"
    override val name        = wrapped.name
    override val description = wrapped.description

    private val _state = MutableStateFlow(snapshot())

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("legacy", wrapped.id),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        runCatching { wrapped.connect() }
        _state.value = snapshot()
        return _state.value
    }

    override suspend fun disconnect() {
        runCatching { wrapped.disconnect() }
        _state.value = snapshot()
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        return when (input.action) {
            "status" -> {
                val s = wrapped.state()
                ConnectorOutput.Success(
                    text = if (s.isConnected) "Connected" else "Not connected",
                    data = mapOf(
                        "id" to s.id,
                        "connected" to s.isConnected.toString(),
                        "last_updated_ms" to s.lastUpdated.toString(),
                    ),
                )
            }
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "Legacy integration '${wrapped.id}' only supports 'status'. " +
                          "Reimplement as a first-class Connector for richer actions.",
            )
        }
    }

    private fun snapshot(): ConnectorState {
        val s = wrapped.state()
        return ConnectorState(
            connected = s.isConnected,
            healthy = s.isConnected,
            statusLine = if (s.isConnected) "Connected" else "Not connected",
            lastUpdatedMs = s.lastUpdated,
        )
    }
}
