# AIRI — Android AI Runtime Interface

AIRI is a production-grade AI assistant for Android that runs inference locally via llama.cpp and optionally routes to cloud providers (OpenAI, Anthropic, Gemini, OpenRouter). It supports voice, agent orchestration, skill execution, memory, and deep system integration.

---

## Platform Requirements

| Item | Version |
|------|---------|
| Android minSdk | 26 (Android 8.0) |
| Android targetSdk | 34 (Android 14) |
| Android compileSdk | 34 |
| NDK | 25.2.9519653 |
| CMake | 3.22.1 |
| Kotlin | 1.9.22 |
| Jetpack Compose BOM | 2023.10.01 |
| AGP | 8.2.2 |

---

## Architecture

AIRI uses a layered architecture with strict dependency direction (UI → Domain → Data):

```
app/src/main/java/com/airi/assistant/
├── ui/                     Compose screens, ViewModels, theme, navigation
│   ├── screens/            All screen composables
│   ├── viewmodel/          ChatViewModel, ModelController
│   ├── theme/              AIRITheme, Color, DesignSystem, ThemePreferences
│   ├── components/         Shared composables (nav bar, overlays)
│   └── activity/           AgentActivityBus, ActivityFeedComposable
├── agent/                  Multi-agent orchestration
│   ├── orchestrator/       ProductionAgentOrchestrator
│   ├── planning/           PlanGenerator, ActionPlan, PlanStep
│   ├── subagent/           SubAgentRegistry + 5 active agents
│   ├── adaptation/         PlannerAdaptationEngine, StrategyEvolutionEngine
│   ├── durable/            DurableTaskManager (WorkManager-backed)
│   └── loop/               AgentLoop, ToolDispatcher
├── execution/              Runtime backend selection
│   ├── backend/            CloudBackend, LocalLlamaBackend
│   ├── cloud/              Adapters for OpenAI, Anthropic, Gemini, OpenRouter
│   └── router/             RuntimeRouter (local vs. cloud decision)
├── voice/                  Full-duplex voice pipeline
│   ├── LiveVoiceService    Foreground service owning voice sessions
│   ├── realtime/           RealtimeVoiceProvider, GeminiLiveProvider, OpenAIRealtimeProvider
│   ├── VoskEngine          On-device STT
│   └── IncrementalTtsEngine Streaming TTS output
├── memory/                 Room database + RAG
│   ├── entity/             ChatMessage, ChatSession, ArtifactEntity
│   ├── dao/                All DAOs (MemoryDao, SessionDao, ArtifactDao…)
│   └── AiriDatabase        Room DB with migrations 1→6
├── connector/              External integration layer
│   ├── api/                LLM connectors (AnthropicProvider, GeminiProvider, OpenAiProvider)
│   ├── app/                App connectors (GitHub, Google, Telegram, Zapier, IFTTT)
│   ├── mcp/                Model Context Protocol (McpConnector, NotionMcpConnector)
│   └── local/              Device connectors (Contacts, Calendar, Clipboard)
├── ai/                     Local model management
│   ├── LlamaManager        llama.cpp JNI bridge
│   ├── skills/             Skill runtime and official skill library
│   └── prompt/             PromptBuilder, DynamicPromptEngine
├── auth/                   Authentication
│   ├── SecureStorage       EncryptedSharedPreferences wrapper
│   └── identity/           BiometricGatekeeper, DeviceBindingService
├── security/               Security enforcement
│   ├── SecretHealthChecker  API key health scanning
│   └── ExecutionFirewall    Agent execution guardrails
├── core/                   Singletons and shared services
│   ├── ServiceLocator      Application-scoped dependency container
│   └── VoiceManager        Audio focus + TTS lifecycle
└── domain/                 Business logic interfaces and cross-cutting concerns
```

---

## Module Relationships

```
ChatViewModel
  ├── binds → LiveVoiceService (voice sessions)
  ├── uses  → HybridOrchestrator (local/cloud routing)
  ├── uses  → CloudBackend (cloud inference)
  ├── uses  → LlamaManager (local inference)
  ├── reads → AiriDatabase (Room, messages/sessions/feedback)
  └── reads → ServiceLocator (adapters, skill registry)

LiveVoiceService
  ├── owns  → LiveVoiceSession (state)
  ├── owns  → VoiceManager (audio focus, TTS)
  ├── uses  → RealtimeVoiceProvider (LocalVoicePipeline | GeminiLiveProvider | OpenAIRealtimeProvider)
  └── routes STT → VoiceAgentRouter → ProductionAgentOrchestrator

ProductionAgentOrchestrator
  ├── uses  → SubAgentRegistry (5 active agents)
  ├── uses  → PlanGenerator (with PlannerAdaptationEngine)
  ├── uses  → DurableTaskManager (checkpointing)
  └── records → StrategyEvolutionEngine, AdaptiveIntelligenceEngine
```

---

## Theme System

Four modes available: **Light**, **Dark**, **System**, **AMOLED**.

Switching is instant — `AIRITheme` reads from `ThemePreferences.themeMode` (a `StateFlow`) and recomposes the entire tree without an app restart.

All screens use `MaterialTheme.colorScheme.*` tokens. No hardcoded hex colors remain in UI code except:
- Semantic greens/reds for status indicators (intentional)
- Terminal screen (always-dark by design)
- Artifact HTML preview (code editors are always dark)
- Third-party brand colors (Zapier cyan)

---

## Localization

Supported languages: **English** (default), **Arabic** (RTL).

All user-visible strings are in `values/strings.xml` and `values-ar/strings.xml`. Both files are kept in sync — as of the current build there are no missing Arabic translations.

Runtime language switching is handled by the Android system and `LanguageManager`. The entire UI responds immediately without restart.

---

## Voice System

### Local Pipeline (default, no API key required)
- STT: **Vosk** (on-device, offline)
- TTS: **Android TTS** via `IncrementalTtsEngine` (streaming)
- Wake word: **Porcupine** (optional) or **OpenWakeWord** (TFLite)

### Cloud Pipeline (requires API key)
- **Gemini Live**: `GeminiLiveProvider` — WebSocket to Gemini BidiGenerateContent API
- **OpenAI Realtime**: `OpenAIRealtimeProvider` — WebSocket to OpenAI Realtime API

Provider selection is persisted in SharedPreferences (`airi_voice/cloud_voice_provider`). On `LiveVoiceService` bind, the saved preference is restored via `restoreProviderPreference()`. Switching in `VoiceSettingsScreen` immediately calls `binder.setRealtimeProvider()`.

---

## Sub-Agents (Active)

| Agent | Capability | Status |
|-------|-----------|--------|
| ResearchAgent | Web research, summarization | Active |
| AndroidAgent | Accessibility-based UI automation | Active |
| ProductivityAgent | Calendar, tasks, reminders | Active |
| MemoryAgent | Episodic/semantic memory retrieval | Active |
| CloudBrowserAgent | Cloud browser + screenshot | Active |

Excluded from registry (delegation shells, no real implementation):
`CodingAgent`, `MediaGenerationAgent`, `DocumentProcessorAgent`, `LocalBrowserOperator`

---

## Database

Room database (`AiriDatabase`) with **6 migrations** (1→6):
- `episodic_memory` (chat messages + feedback column added in migration 5→6)
- `chat_sessions`
- `workspace_artifact`
- `message_embedding`
- `context_cache`
- `behavior_stats`
- `usage_stats`
- `audit_log`

Encrypted at rest via **SQLCipher** (AES-256).

---

## Security

- API keys: AES256_GCM via `EncryptedSharedPreferences`
- File sharing: `FileProvider` with restricted paths (`attachments/`, `cache/`)
- All `PendingIntent`s use `FLAG_IMMUTABLE`
- WebView: JS disabled, file access blocked, URL navigation blocked
- SSL pinning: 4 hosts pinned (OpenAI, Anthropic, Gemini, OpenRouter) — **verify pins annually before release**
- Room: All queries parameterized via `@Query(:param)` — no SQL injection risk
- Exported components: only `MainActivity` (LAUNCHER) and `AiriAccessibilityService` (system-only binding)

---

## Build

### Debug
```bash
./gradlew assembleDebug
```
Requires: Android SDK, NDK 25.2.9519653, CMake 3.22.1.

### Release
```bash
export KEYSTORE_BASE64="..."
export STORE_PASSWORD="..."
export KEY_ALIAS="airi"
export KEY_PASSWORD="..."
./gradlew assembleRelease
```

### Native library
`libairi_native.so` is compiled from `app/src/main/cpp/CMakeLists.txt` (llama.cpp). The `airiVerifyNativeInApk` Gradle task asserts it is present and ≥ 1 MB after each assembly.

---

## Redmi / MIUI / HyperOS Compatibility

- `AccessibilityScopePolicy` includes `com.miui.home` launcher package
- `DefaultAssistantManager.openMiuiAutostartSettings()` opens the MIUI/HyperOS autostart whitelist for battery restriction workaround
- Call `DefaultAssistantManager.isMiuiDevice()` before showing MIUI-specific prompts
- Background execution: `LiveVoiceService` and `HotwordService` are foreground services — immune to MIUI background kill when the user grants autostart permission

---

## External Requirements

The following services require credentials or external infrastructure. The app degrades gracefully when they are absent.

### Critical (core functionality blocked)
| Requirement | Purpose | Where to get |
|-------------|---------|--------------|
| OpenAI / Anthropic / Gemini API key | Cloud LLM inference | platform.openai.com / console.anthropic.com / aistudio.google.com |
| SSL pin re-verification (annual) | TLS security | Run `openssl s_client` on each host — see `LlmCertPins.kt` |

### High (major features blocked)
| Requirement | Purpose | Where to get |
|-------------|---------|--------------|
| GitHub OAuth App or PAT | Git write operations, Repository Browser | github.com/settings/developers |
| Gemini Live API key | Real-time voice (Gemini) | aistudio.google.com |
| OpenAI Realtime API key | Real-time voice (OpenAI) | platform.openai.com |
| Picovoice AccessKey | "Hey AIRI" wake word | picovoice.ai |

### Medium
| Requirement | Purpose |
|-------------|---------|
| Firebase Firestore rules | Multi-device conversation sync |
| Play Integrity API project link | Device attestation |
| Google OAuth credentials | Calendar/Drive connector |

### Low
| Requirement | Purpose |
|-------------|---------|
| Telegram Bot Token | Telegram messaging |
| OpenRouter API key | 100+ model routing |
| N8N / Zapier / IFTTT webhooks | Automation triggers |
| MCP server endpoint | Model Context Protocol tools |

---

## Remaining Code Gaps

| Gap | Effort |
|-----|--------|
| `WorkspaceScreen.sessionType` unused — prototype/wireframe identical | Small |
| `PrivacyDataSettingsScreen` data export/audit not wired | Medium |
| `DeveloperCenterScreen` diagnostics (CrashReportStore, SkillAuditLogger) not wired | Medium |
| Cloud sync `syncConversations()` not implemented | Large |

---

## Production Readiness

- Theme: **Complete** — Light/Dark/AMOLED/System, instant switching
- Localization: **Complete** — English + Arabic, all strings in resources
- Voice: **Complete** — Local (Vosk), Gemini Live, OpenAI Realtime, all wired
- Security: **Production-grade** — encryption, pinning, FileProvider, PendingIntent, WebView
- Navigation: **Complete** — all routes registered and functional
- Build: **Ready** — debug and release, NDK native library verified
- Redmi/MIUI: **Compatible** — autostart helper, correct launcher package
