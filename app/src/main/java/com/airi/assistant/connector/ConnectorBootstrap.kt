package com.airi.assistant.connector

import android.content.Context
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.connector.accessibility.AccessibilityAutomationConnector
import com.airi.assistant.connector.api.HttpApiConnector
import com.airi.assistant.connector.api.RemoteLlmConnector
import com.airi.assistant.connector.browser.BrowserConnector
import com.airi.assistant.connector.legacy.IntegrationConnectorAdapter
import com.airi.assistant.connector.local.AndroidIntentConnector
import com.airi.assistant.connector.local.DocumentConnector
import com.airi.assistant.connector.local.FilesystemConnector
import com.airi.assistant.connector.local.GitConnector
import com.airi.assistant.connector.local.MemoryRagConnector
import com.airi.assistant.connector.local.SchedulerConnector
import com.airi.assistant.connector.local.VoiceConnector
import com.airi.assistant.connector.mcp.InMemoryMcpConnector
import com.airi.assistant.connector.system.DeviceControlConnector
import com.airi.assistant.connector.system.LogcatConnector
import com.airi.assistant.connector.system.ShellSandboxConnector
import com.airi.assistant.connector.system.SystemInfoConnector
import com.airi.assistant.connector.system.TerminalConnector
import com.airi.assistant.connector.vision.OCRConnector
import com.airi.assistant.connector.vision.VisionReasoningConnector
import com.airi.assistant.integration.GithubIntegration
import com.airi.assistant.integration.NotionIntegration
import com.airi.assistant.integration.TelegramIntegration
import com.airi.assistant.memory.rag.RagRetriever
import com.airi.assistant.memory.repository.MemoryManager

/**
 * Wires the full set of built-in [Connector]s into a [ConnectorRegistry].
 *
 * Called once from [com.airi.assistant.core.ServiceLocator] when the
 * registry is first requested. Adding a new built-in connector is a
 * one-line change here — register it and it shows up in the UI tab
 * matching its [ConnectorType].
 *
 * No connector is *connected* eagerly — the registry stores them, the
 * UI / agent decides when to call [Connector.connect]. That keeps app
 * startup cheap.
 *
 * ## Connector inventory (Phase 3 — 14 built-in connectors)
 *
 * API:
 *   - [RemoteLlmConnector]   — cloud LLM providers (OpenAI, Anthropic, etc.)
 *   - [HttpApiConnector]     — generic REST HTTP calls
 *
 * LOCAL:
 *   - [AndroidIntentConnector] — Android Intents (open app, URL, settings)
 *   - [VoiceConnector]         — on-device speech / mtmd pipeline
 *   - [FilesystemConnector]    — scoped file I/O (read/write/list)
 *   - [DocumentConnector]      — text extraction from shared URIs
 *   - [SchedulerConnector]     — AlarmManager one-shot + repeating alarms
 *   - [GitConnector]           — git version control via ProcessBuilder
 *   - [MemoryRagConnector]     — on-device RAG over past conversations
 *
 * SYSTEM:
 *   - [SystemInfoConnector]    — battery + network telemetry
 *   - [DeviceControlConnector] — clipboard, volume, Wi-Fi, device info
 *   - [LogcatConnector]        — Android system log reader
 *   - [TerminalConnector]      — sandboxed shell command execution
 *   - [ShellSandboxConnector]  — allowlisted shell command sandbox (POSIX utilities)
 *
 * MCP:
 *   - [InMemoryMcpConnector]   — built-in echo demo; replace with real server
 *
 * APP (legacy bridge):
 *   - GitHub, Telegram, Notion (via [IntegrationConnectorAdapter])
 */
object ConnectorBootstrap {

    fun installDefaults(
        appContext: Context,
        registry: ConnectorRegistry,
        llmProviders: List<RemoteLlmConnector.Provider> = emptyList(),
        voiceBackend: VoiceConnector.VoiceBackend? = null,
        ragRetriever: RagRetriever? = null,
        memoryManager: MemoryManager? = null,
        accessibilityEngine: AccessibilityExecutionEngine? = null,
    ) {
        // ── API tab ─────────────────────────────────────────────────────────
        registry.register(RemoteLlmConnector(providers = llmProviders))
        registry.register(HttpApiConnector())

        // ── LOCAL tab ───────────────────────────────────────────────────────
        registry.register(AndroidIntentConnector(appContext))
        registry.register(VoiceConnector(backend = voiceBackend))
        registry.register(FilesystemConnector(appContext))
        registry.register(DocumentConnector(appContext))
        registry.register(SchedulerConnector(appContext))
        registry.register(GitConnector())

        if (ragRetriever != null && memoryManager != null) {
            registry.register(MemoryRagConnector(ragRetriever, memoryManager))
        }

        // ── SYSTEM tab ──────────────────────────────────────────────────────
        registry.register(SystemInfoConnector(appContext))
        registry.register(DeviceControlConnector(appContext))
        registry.register(LogcatConnector())
        registry.register(TerminalConnector())
        registry.register(ShellSandboxConnector())

        // ── MCP tab ─────────────────────────────────────────────────────────
        registry.register(InMemoryMcpConnector())

        // ── Phase 3: Advanced Tool Ecosystem ────────────────────────────────
        registry.register(BrowserConnector(appContext))
        registry.register(OCRConnector(appContext))
        registry.register(VisionReasoningConnector(appContext))
        if (accessibilityEngine != null) {
            registry.register(AccessibilityAutomationConnector(accessibilityEngine))
        }

        // ── APP tab (legacy bridge) ─────────────────────────────────────────
        val legacyPrefs = appContext.getSharedPreferences(
            "airi_integrations", Context.MODE_PRIVATE,
        )
        registry.register(IntegrationConnectorAdapter(GithubIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(TelegramIntegration(legacyPrefs)))
        registry.register(IntegrationConnectorAdapter(NotionIntegration(legacyPrefs)))
    }
}
