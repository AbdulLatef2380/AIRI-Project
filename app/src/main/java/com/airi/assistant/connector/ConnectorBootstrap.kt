package com.airi.assistant.connector

import android.content.Context
import com.airi.assistant.connector.api.RemoteLlmConnector
import com.airi.assistant.connector.legacy.IntegrationConnectorAdapter
import com.airi.assistant.connector.local.AndroidIntentConnector
import com.airi.assistant.connector.local.VoiceConnector
import com.airi.assistant.connector.mcp.InMemoryMcpConnector
import com.airi.assistant.connector.system.SystemInfoConnector
import com.airi.assistant.integration.GithubIntegration
import com.airi.assistant.integration.NotionIntegration
import com.airi.assistant.integration.TelegramIntegration

/**
 * Wires the default set of [Connector]s into a [ConnectorRegistry].
 *
 * Called once from [com.airi.assistant.core.ServiceLocator] when the
 * registry is first requested. Adding a new built-in connector is a
 * one-line change here — register it and it shows up in the UI tab
 * matching its [ConnectorType].
 *
 * No connector is *connected* eagerly — the registry stores them, the
 * UI / agent decides when to call [Connector.connect]. That keeps app
 * startup cheap.
 */
object ConnectorBootstrap {

    fun installDefaults(
        appContext: Context,
        registry: ConnectorRegistry,
        llmProviders: List<RemoteLlmConnector.Provider> = emptyList(),
        voiceBackend: VoiceConnector.VoiceBackend? = null,
    ) {
        // ── API tab ─────────────────────────────────────────────────
        // RemoteLlmConnector is registered even with zero providers so
        // the UI shows the slot. providers can be supplied later via
        // additional registry.register(...) calls.
        registry.register(RemoteLlmConnector(providers = llmProviders))

        // ── LOCAL tab ───────────────────────────────────────────────
        registry.register(AndroidIntentConnector(appContext))
        registry.register(VoiceConnector(backend = voiceBackend))

        // ── SYSTEM tab ──────────────────────────────────────────────
        registry.register(SystemInfoConnector(appContext))

        // ── MCP tab ─────────────────────────────────────────────────
        registry.register(InMemoryMcpConnector())

        // ── APP tab (legacy bridge) ─────────────────────────────────
        // Wrap the existing legacy Integration instances so users see
        // them in the new Connectors UI without breaking anything that
        // still calls IntegrationsViewModel directly.
        val legacyPrefs = appContext.getSharedPreferences(
            "airi_integrations", Context.MODE_PRIVATE,
        )
        registry.register(IntegrationConnectorAdapter(GithubIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(TelegramIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(NotionIntegration(legacyPrefs)))
    }
}
