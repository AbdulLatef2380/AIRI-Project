package com.airi.assistant.connector

import android.content.Context
import com.airi.assistant.connector.api.RemoteLlmConnector
import com.airi.assistant.connector.legacy.IntegrationConnectorAdapter
import com.airi.assistant.connector.local.AndroidIntentConnector
import com.airi.assistant.connector.local.ClipboardConnector
import com.airi.assistant.connector.local.ContactsConnector
import com.airi.assistant.connector.local.DeviceAppsConnector
import com.airi.assistant.connector.local.VoiceConnector
import com.airi.assistant.connector.mcp.InMemoryMcpConnector
import com.airi.assistant.connector.system.SystemInfoConnector
import com.airi.assistant.integration.GithubIntegration
import com.airi.assistant.integration.NotionIntegration
import com.airi.assistant.integration.TelegramIntegration

/**
 * Wires the full set of [Connector]s into a [ConnectorRegistry].
 *
 * Called once from [com.airi.assistant.core.ServiceLocator] on first
 * registry access. Adding a new connector = one register() call here.
 */
object ConnectorBootstrap {

    fun installDefaults(
        appContext: Context,
        registry: ConnectorRegistry,
        llmProviders: List<RemoteLlmConnector.Provider> = emptyList(),
        voiceBackend: VoiceConnector.VoiceBackend? = null,
    ) {
        // ── API / LLM tab ────────────────────────────────────────────
        registry.register(RemoteLlmConnector(providers = llmProviders))

        // ── LOCAL tab ────────────────────────────────────────────────
        registry.register(AndroidIntentConnector(appContext))
        registry.register(VoiceConnector(backend = voiceBackend))
        registry.register(ClipboardConnector(appContext))
        registry.register(DeviceAppsConnector(appContext))
        registry.register(ContactsConnector(appContext))

        // ── SYSTEM tab ───────────────────────────────────────────────
        registry.register(SystemInfoConnector(appContext))

        // ── MCP tab ──────────────────────────────────────────────────
        registry.register(InMemoryMcpConnector())

        // ── APP / legacy bridge tab ──────────────────────────────────
        val legacyPrefs = appContext.getSharedPreferences(
            "airi_integrations", Context.MODE_PRIVATE,
        )
        registry.register(IntegrationConnectorAdapter(GithubIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(TelegramIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(NotionIntegration(legacyPrefs)))
    }
}
