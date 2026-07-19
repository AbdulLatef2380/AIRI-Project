# AIRI — On-Device AI Assistant

Android AI assistant supporting local llama.cpp inference and multiple cloud LLM providers. Production-ready for Android 8.0+ (API 26+).

---

## Architecture

```
ChatScreen / ChatViewModel
        │
        ▼
ProductionAgentOrchestrator
        │
        ▼
AgentLoop  ──────────────────────── ConversationHistory
        │                                    │
        ▼                                    ▼
HybridOrchestrator              ExecutionRequest
     │         │                 (conversationHistory field)
     ▼         ▼
LocalLlamaBackend   CloudBackend
     │                   │
     ▼                   ▼
LlamaManager      GeminiAdapter / OpenAIAdapter
  (llamaDispatcher)    AnthropicAdapter / OpenRouterAdapter
     │
     ▼
LlamaNative (JNI)
llama.cpp
```

---

## Supported AI Providers

| Provider | Type | Multi-turn | Streaming |
|---|---|---|---|
| llama.cpp (local) | Local | ✅ KV-cache session reuse | ✅ |
| Gemini 2.0 Flash | Cloud | ✅ `contents` array | ✅ SSE |
| OpenAI GPT-4o | Cloud | ✅ `messages` array | ✅ SSE |
| Anthropic Claude | Cloud | ✅ `messages` array | ✅ SSE |
| OpenRouter | Cloud | ✅ Inherits OpenAI | ✅ SSE |

---

## Local Inference Pipeline

```
User message
    → AgentLoop.callLLM()
    → HybridOrchestrator → RuntimeRouter → LocalLlamaBackend
    → LlamaManager.generateStream() [llamaDispatcher — 1 thread]
        → PromptBudgetLedger.forBudget()   ← thermal throttle (generation only)
        → reconcileSession()               ← KV cache reuse check
            session_primed=true  → appendUserTurn() (~30 tokens, fast)
            session_primed=false → fullReset() + cold rebuild (slow)
        → [pre-prefill cancel check]       ← exits if cancelled before JNI
        → LlamaNative.appendUserTurn()     ← JNI prefill
        → LlamaNative.generateNextTokens() ← token streaming
        → Handler.post { onComplete() }    ← non-blocking Main dispatch
```

### Thermal Throttle

At thermal throttle level REDUCE (`thermalFactor = 0.5`), the generation reserve is halved (fewer output tokens generated), but the **input context window is unchanged**. This preserves KV-cache session reuse and prevents the previously observed ANR from full cold rebuilds on every message.

### KV-Cache Session Reuse

`LlamaManager` tracks `sessionPrimed` and `primedHistory`. On the second and subsequent messages:
- If history matches and context fits → `appendUserTurn()` only (~30 tokens)
- If mismatch or overflow → `fullReset()` + cold rebuild (all history from scratch)

---

## Cloud Inference — Multi-Turn

All cloud providers are stateless REST APIs. Every call must include the full conversation history.

```
AgentLoop builds:
  conversationHistory = [
    ConversationTurn("user", "Hi"),
    ConversationTurn("assistant", "Hello! How can I help?"),
  ]

ExecutionRequest(
    prompt              = "Who are you?",
    conversationHistory = [prior turns...]
)

GeminiAdapter sends:
  "contents": [
    {"role": "user",  "parts": [{"text": "Hi"}]},
    {"role": "model", "parts": [{"text": "Hello! How can I help?"}]},
    {"role": "user",  "parts": [{"text": "Who are you?"}]}
  ]
```

Gemini uses `"model"` for assistant role. OpenAI and Anthropic use `"assistant"`.

---

## Cancellation System

```
User taps Cancel
    → ChatViewModel.cancelGeneration()
    → HybridOrchestrator.cancel()
        → cancelled.set(true)
        → activeBackend_.cancelStream()        ← non-blocking
            → LocalLlamaBackend.cancelStream()
                → LlamaManager.cancelStream()
                    → cancelRequested.set(true)
                    → LlamaNative.nativeCancel()  ← sets g_cancel_requested in JNI
```

**Pre-prefill cancel check:** If cancel is requested before the JNI prefill begins (during Kotlin-side prompt assembly or `reconcileSession()`), `LlamaManager` detects this and exits immediately without entering `llama_decode()`. Cancel latency in this case: < 100ms.

**Mid-prefill cancel:** The native `g_cancel_requested` flag is checked between 64-token chunks. On slow hardware (Mediatek, ~18s/chunk) this may still exceed Android's 5-second ANR threshold. See Known Limitations.

---

## Theme System

AIRI supports Light, Dark, and AMOLED themes via Material3 `MaterialTheme.colorScheme`.

All production UI colors are expressed as semantic tokens:
- `MaterialTheme.colorScheme.primary` — accent
- `MaterialTheme.colorScheme.onSurface` — text/icons on surfaces
- `MaterialTheme.colorScheme.surface` / `background` — container colors
- `MaterialTheme.colorScheme.outline` — borders and dividers

**Intentional branding constants** (defined in `theme/Color.kt`, do not change):
- `CosmicAccent` — brand accent purple (used as `primary` seed color)
- `CosmicBlack` — AMOLED background (`0xFF080B14`)

**Zero hardcoded colors in production UI** — all 365 prior `Color.White`/`Color.Black`/hex literals replaced.

---

## Localization

Four locales supported: English (`values/`), Arabic (`values-ar/`), Spanish (`values-es/`), Chinese (`values-zh/`).

All locales maintain exact key parity: **967 strings, 0 gap, 0 duplicates**.

Language switching takes effect immediately via `LanguageManager.applyLocale()` + `Activity.recreate()`. No string caching in ViewModels.

---

## Debugging Workflow

### Logcat Tags

| Tag | Meaning |
|---|---|
| `AIRI_PROOF GEN_START` | Generation started; shows `primed_history` and `chat_history` |
| `AIRI_PROOF STATE_PREFLIGHT` | Session bookkeeping; check `session_primed` |
| `AIRI_PROOF PREFILL_BEGIN` | Native prefill starting; `n_past_before=0` means cold rebuild |
| `AIRI_PROOF PREFILL_CHUNK` | 64-token chunk; interval reveals CPU speed |
| `AIRI_PROOF PREFILL_CANCELLED_BEFORE_NATIVE` | Cancel caught before JNI entry |
| `AIRI_PROOF GEN_END` | Complete; shows `tps` and `first_token_ms` |
| `AIRI_PROOF BUDGET_THERMAL_RESERVE` | Thermal state; `actual_ctx_reserve=0` = fixed |
| `AIRI_GeminiAdapter` | Cloud request; shows history turn count |
| `AIRI_RuntimeRouter` | Backend selection |

### Common Failure Patterns

| Symptom | Logcat Evidence | Root Cause |
|---|---|---|
| ANR on second local message | `PREFILL_CHUNK` continues after `GEN_CANCEL_REQUESTED` | Native intra-chunk cancel not implemented in LlamaBridge.cpp |
| Second Gemini message has no context | HTTP 200 but model answers without history | Verify `history` param populated in caller |
| Every message does cold rebuild | `STATE_PREFLIGHT session_primed=false` repeated | Check thermalFactor and nCtx |
| Voice screen crashes | `IllegalArgumentException: Service not registered` | bindService returned false — check `bound` guard |

---

## Known Limitations

1. **Native cancel latency**: The `llama_decode()` function cannot be interrupted mid-call. Cancel latency = one chunk duration (~18s on low-end CPU). Requires editing `LlamaBridge.cpp` to check `g_cancel_requested` after each batch. Until this is done, ANR is possible on slow devices when cancelling during prefill.

2. **nCtx=1536 in BALANCED mode**: Conservative for Gemma 2 2B on 8GB RAM. Consider raising to 2048–4096 for MID/HIGH device tiers in `RuntimeSupervisor`.

3. **Play Integrity**: `INTEGRITY_TOKEN_REQUEST_FAILED -16` — cloud project number in Play Console not configured. Non-critical for inference.

4. **OpenRouter multi-turn**: Tested by inheritance only (extends OpenAIAdapter). Verify with a live OpenRouter API key.

---

## Project Structure

```
app/src/main/java/com/airi/assistant/
├── agent/         Multi-agent orchestration, AgentLoop, DurableTaskManager
├── ai/            LlamaManager, LlamaNative, PromptBudgetLedger, ModelManager
├── connector/     Cloud provider connectors (Gemini, OpenAI, Anthropic, etc.)
├── core/          VoiceManager, ServiceLocator
├── execution/     HybridOrchestrator, RuntimeBackend, ExecutionRequest, cloud adapters
├── memory/        Episodic and semantic memory
├── settings/      User preferences
├── skills/        Skill system, SkillWizard, SkillExecutor
├── system/        LanguageManager, DefaultAssistantManager
├── ui/            All Compose screens, components, themes
└── voice/         LiveVoiceService, VoskEngine, IncrementalTtsEngine
```

---

## Build Requirements

- Android Studio Hedgehog or later
- JDK 21
- Gradle 8.5
- Android SDK 34 (compileSdk), minSdk 26
- NDK r26+ (for llama.cpp JNI layer)
- `local.properties`: `sdk.dir=<path-to-android-sdk>`

```bash
./gradlew clean assembleDebug
./gradlew assembleRelease
```
