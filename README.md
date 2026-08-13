# AIRI

AIRI is an Android AI assistant built with Kotlin, Jetpack Compose, Room, WorkManager, and a hybrid inference layer for local `llama.cpp` and supported cloud providers. The project targets Android API 26+ and uses API 34 for compilation.

> **Current engineering status:** the source tree contains the core feature set and the static verification suite passes. A complete Kotlin build has **not** been verified in this workspace because the Android Gradle Plugin `8.2.2` is unavailable locally and the environment cannot resolve the configured plugin repositories. Do not label a release build as production-ready until `:app:assembleDebug`, instrumentation tests, and device validation succeed in a connected build environment.

## What is implemented

| Area | Current behavior | Verification status |
|---|---|---|
| Chat execution | Hybrid local/cloud orchestration with streaming and user cancellation | Source-level verification completed; full build pending |
| Cancellation | A generation ID owns each text or image request; late callbacks are gated after cancellation | Static path check completed |
| Memory | Chat history is bounded per session; long-term facts require explicit user intent and reject sensitive content | Static path check completed |
| RAG | Semantic retrieval is scoped to the current session; explicit memories can be selected as untrusted reference data | Static path check completed |
| Input shortcuts | `/` selects an enabled, connected skill; `@` selects saved long-term knowledge | Static path check completed |
| Voice | Local Vosk STT and Android TTS are the active voice-chat route; wake-word detection has a cooldown | Static path check completed |
| Scheduled tasks | WorkManager-backed one-time and periodic jobs persist metadata and the latest recorded outcome | Static path check completed |
| Profile deletion | Profile deletion uses the full data-deletion coordinator rather than Firebase-only deletion | Static path check completed |

## Explicit limitations

| Limitation | Consequence | Required follow-up |
|---|---|---|
| Android Gradle Plugin is not cached in this workspace | Kotlin compilation could not be run here | Run the build in Android Studio or an online CI runner with Google Maven access |
| Realtime cloud voice providers expose an interface but are not wired to microphone PCM capture and AudioTrack playback | Cloud realtime voice is intentionally not advertised as an active chat path | Implement and test the end-to-end transport before enabling it in the UI |
| Wake-word operation needs either a valid OpenWakeWord model or Picovoice credentials and a keyword model | Wake word may remain unavailable on a fresh installation | Configure voice assets and validate on physical devices |
| Native inference and device performance vary by model and hardware | Latency, thermal behavior, and memory pressure need device testing | Test representative low-, mid-, and high-tier devices |

## Architecture

```text
ChatScreen / ChatViewModel
        │
        ├── input directives: /skill:<id>, @knowledge:<id>
        ▼
AgentLoop → HybridOrchestrator
        ├── LocalLlamaBackend → LlamaManager → JNI → llama.cpp
        └── Cloud backends → configured provider adapters

MemoryManager
        ├── bounded session transcript
        ├── MemoryAdmissionPolicy
        ├── explicit long-term memory
        └── session-scoped EmbeddingService / RagRetriever
```

## Privacy and memory model

The conversation transcript is distinct from long-term memory. AIRI may retain recent chat turns within a bounded session window. A message is eligible for embedding only when it is meaningful, within size limits, and does not contain detected sensitive patterns such as credentials, email addresses, phone numbers, or payment-card-like numbers. Durable extracted facts require an explicit memory request and are restricted to a small allow-list of non-sensitive categories.

RAG never scans other sessions by default. Retrieved context is framed as **untrusted historical reference data**, not as executable instructions. Selecting `@` in the composer inserts one explicit saved-memory reference for the current request.

## Composer shortcuts

| Input | Result |
|---|---|
| `/` followed by a query | Shows enabled and connected skills. Selecting one inserts an internal `/skill:<id>` directive. |
| `@` followed by a query | Shows saved long-term knowledge in the current session. Selecting one inserts an internal `@knowledge:<id>` directive. |
| Stop button during generation | Cancels the current generation ID and suppresses late text, error, or image callbacks. |

The directives are removed before the user’s message is stored or sent as the visible prompt. They supply bounded execution context only after the selected skill or memory ID is revalidated.

## Build requirements

| Requirement | Version / note |
|---|---|
| JDK | 17 or newer; the project compiles Kotlin for JVM 17 |
| Gradle | 8.5 wrapper distribution |
| Android SDK | API 34 |
| Android NDK | `25.2.9519653` |
| CMake | `3.22.1` |
| Network access | Required on a fresh machine to resolve Android Gradle Plugin and dependencies |

Create `local.properties` with `sdk.dir=<path-to-android-sdk>`, then run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

For this workspace, a local Gradle 8.5 distribution exists at `/home/ubuntu/tools/gradle-8.5`, but the required Android Gradle Plugin is not cached. The failure log is documented in `docs/BUILD_STATUS.md`.

## Documentation map

| Document | Purpose |
|---|---|
| `docs/BUILD_STATUS.md` | Exact build-verification result and environment limitation |
| `docs/IMPLEMENTATION_STATUS.md` | Status of the sixteen requested improvement areas |
| `docs/RUNBOOK.md` | Operational and developer runbook |
| `app/src/main/java/com/airi/assistant/*/README.md` | Package-level ownership and limitations |
| `docs/archive/` and `reports/` | Historical reports; not current readiness evidence |

## Repository layout

```text
app/src/main/java/com/airi/assistant/
├── agent/       Agent loop, sub-agents, planning, scheduling
├── ai/          Prompting, model runtime, skills
├── core/        App services and service locator
├── execution/   Hybrid execution and provider backends
├── memory/      Room persistence, admission policy, embeddings, RAG
├── security/    Security and privacy controls
├── ui/          Compose screens, navigation, themes
└── voice/       Local voice pipeline, wake word, live session service
```

## Verification performed in this workspace

The static verifier at `tools/verify_core_changes.py` passed **23/23** checks covering cancellation ownership, callback gating, memory admission, session-scoped retrieval, untrusted RAG context, `/` and `@` wiring, logical text alignment, profile deletion coordination, voice stop behavior, wake-word cooldown, scheduler outcomes and unique work, OAuth PKCE, voice-session audio ownership, Arabic memory tokenization, runtime-marker normalization, and resource-key parity across English, Arabic, Spanish, and Chinese. This is source-level evidence only.

This is **not a substitute for a successful Android build or device test**.
