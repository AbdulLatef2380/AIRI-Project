package com.airi.assistant.connector

import android.content.Context
import android.util.Log
import com.airi.assistant.connector.N8nConnector
import com.airi.assistant.connector.api.RemoteLlmConnector
import com.airi.assistant.connector.app.GitHubConnector
import com.airi.assistant.connector.app.GoogleConnector
import com.airi.assistant.connector.app.IftttConnector
import com.airi.assistant.connector.app.TelegramConnector
import com.airi.assistant.connector.app.ZapierConnector
import com.airi.assistant.connector.local.AndroidIntentConnector
import com.airi.assistant.connector.local.ClipboardConnector
import com.airi.assistant.connector.local.ContactsConnector
import com.airi.assistant.connector.local.DeviceAppsConnector
import com.airi.assistant.connector.local.VoiceConnector
import com.airi.assistant.connector.mcp.NotionMcpConnector
import com.airi.assistant.connector.system.SystemInfoConnector
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.voice.VoskVoiceBackend

/**
 * Wires the full set of [Connector]s into a [ConnectorRegistry].
 *
 * Called once from [com.airi.assistant.core.ServiceLocator] on first
 * registry access. Adding a new connector = one register() call here.
 *
 * AP-06: Removed all IntegrationConnectorAdapter fallback paths. These were
 * status-only adapters that showed a phantom "Connected" state while silently
 * returning ConnectorResult.Failure on every real action. After AP-04 guarantees
 * SecureStorage is always available via ServiceLocator, these fallback paths
 * are dead. If SecureStorage is somehow unavailable, connectors are skipped
 * (not registered as phantom-connected) and the omission is logged.
 *
 * AP-10: GoogleConnector registered as connector #14, resolving
 * GmailAssistantSkill / CalendarEventsSkill / DriveSearchSkill runtime crashes.
 */
object ConnectorBootstrap {

    private const val TAG = "ConnectorBootstrap"

    fun installDefaults(
        appContext: Context,
        registry: ConnectorRegistry,
        authManager: ConnectorAuthManager,
        llmProviders: List<RemoteLlmConnector.Provider> = emptyList(),
        voiceBackend: VoiceConnector.VoiceBackend? = VoskVoiceBackend(appContext),
        // secureStorage param retained for callers that pass it explicitly;
        // falls back to ServiceLocator.secureStorage if null (AP-04 guarantee).
        secureStorage: com.airi.assistant.auth.SecureStorage? = null,
        durableTaskManager: com.airi.assistant.agent.durable.DurableTaskManager? = null,
    ) {
        val storage = secureStorage ?: runCatching { ServiceLocator.secureStorage }.getOrNull()

        // ── API / LLM tab ─────────────────────────────────────────────────────
        registry.register(RemoteLlmConnector(providers = llmProviders))

        // ── LOCAL tab ─────────────────────────────────────────────────────────
        registry.register(AndroidIntentConnector(appContext))
        registry.register(VoiceConnector(backend = voiceBackend))
        registry.register(ClipboardConnector(appContext))
        registry.register(DeviceAppsConnector(appContext))
        registry.register(ContactsConnector(appContext))

        // ── SYSTEM tab ────────────────────────────────────────────────────────
        registry.register(SystemInfoConnector(appContext))

        // ── APP tab ───────────────────────────────────────────────────────────
        registry.register(ZapierConnector(authManager))
        registry.register(IftttConnector(authManager))
        // AP-19: N8nConnector — first-class workflow automation via configurable webhook URL.
        // Replaces the hardcoded localhost:5678 N8nIntegration usage.
        registry.register(N8nConnector(authManager))

        // P1-7 / AP-06: First-class GitHubConnector — no legacy adapter fallback.
        registry.register(GitHubConnector(authManager, durableTaskManager))

        // B-20 / AP-06: TelegramConnector — first-class; legacy adapter removed.
        // AP-04 guarantees SecureStorage is always available; log and skip if not.
        if (storage != null) {
            registry.register(TelegramConnector(storage))
        } else {
            Log.e(TAG, "AP-06: SecureStorage unavailable — Telegram connector NOT registered (secure storage error)")
        }

        
        if (storage != null) {
            registry.register(NotionMcpConnector(storage))
        } else {
            Log.e(TAG, "AP-06: SecureStorage unavailable — Notion connector NOT registered (secure storage error)")
        }

        // AP-10: GoogleConnector — registered as connector #14.
        // Resolves GmailAssistantSkill, CalendarEventsSkill, DriveSearchSkill runtime crashes.
        registry.register(GoogleConnector(ServiceLocator.googleAuthService))
    }
}
