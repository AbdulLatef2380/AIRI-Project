package com.airi.assistant.connector

import android.content.Context
import com.airi.assistant.connector.api.RemoteLlmConnector
import com.airi.assistant.connector.app.GitHubConnector
import com.airi.assistant.connector.app.TelegramConnector
import com.airi.assistant.connector.legacy.IntegrationConnectorAdapter
import com.airi.assistant.connector.local.AndroidIntentConnector
import com.airi.assistant.connector.local.ClipboardConnector
import com.airi.assistant.connector.local.ContactsConnector
import com.airi.assistant.connector.local.DeviceAppsConnector
import com.airi.assistant.connector.local.VoiceConnector
import com.airi.assistant.connector.mcp.InMemoryMcpConnector
import com.airi.assistant.connector.system.SystemInfoConnector
import com.airi.assistant.integration.NotionIntegration
import com.airi.assistant.voice.VoskVoiceBackend

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
        authManager: ConnectorAuthManager,           // P1-7: needed for GitHubConnector
        llmProviders: List<RemoteLlmConnector.Provider> = emptyList(),
        // B-08: VoskVoiceBackend wires real Vosk STT to the connector bus.
        // Falls back gracefully when no model is installed (warmUp returns false).
        voiceBackend: VoiceConnector.VoiceBackend? = VoskVoiceBackend(appContext),
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

        // ── APP tab ──────────────────────────────────────────────────
        // P1-7: Replace legacy GitHub adapter with first-class GitHubConnector.
        // GitHubConnector supports list_repos, list_issues, create_issue,
        // search_code, get_file, list_prs — the full agent-usable capability set.
        // Token is stored/retrieved via ConnectorAuthManager (EncryptedSharedPreferences).
        registry.register(GitHubConnector(authManager))

        // B-20: TelegramConnector — first-class, replaces legacy status-only adapter
        val storeSL = runCatching {
            com.airi.assistant.core.ServiceLocator.secureStorage
        }.getOrNull()
        if (storeSL != null) {
            registry.register(TelegramConnector(storeSL))
        } else {
            val tgPrefs = appContext.getSharedPreferences("airi_integrations", Context.MODE_PRIVATE)
            registry.register(IntegrationConnectorAdapter(
                com.airi.assistant.integration.TelegramIntegration(tgPrefs)
            ))
        }

        // Notion remains on legacy adapter (P3 — no API implementation yet)
        val notionPrefs = appContext.getSharedPreferences("airi_integrations", Context.MODE_PRIVATE)
        registry.register(IntegrationConnectorAdapter(NotionIntegration(notionPrefs)))
    }
}
