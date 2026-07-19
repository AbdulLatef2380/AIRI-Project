# AIRI Changelog

## [Stability Release] — July 2026

### Critical Fixes

#### P0: Voice Screen Crash
- **VoiceSettingsScreen**: `unbindService()` now guarded with `var bound` flag and `try/catch(IllegalArgumentException)`. The composable can dispose safely whether or not `onServiceConnected` has fired.
- **VoiceSettingsScreen**: Two hardcoded English snackbar strings replaced with `R.string` resources.

#### P1: Gemini Second-Message Context Loss
- **GeminiAdapter**: `buildRequestBody()` now sends full conversation history in the `contents` array on every request. Role "assistant" mapped to "model" per Gemini API spec.
- **GeminiProvider**: Multi-turn history via `params["history_json"]` parameter. Prior turns prepended before current user message.
- **OpenAIAdapter**: `buildRequestBody()` sends system → history → current user turn in the `messages` array.
- **AnthropicAdapter**: Same pattern as OpenAI; system prompt remains top-level field per Anthropic spec.
- **OpenRouterAdapter**: Inherits fix from OpenAIAdapter.
- **ExecutionRequest**: Added `conversationHistory: List<ConversationTurn>` field.
- **AgentLoop**: Populates `conversationHistory` from internal turn history before creating each `ExecutionRequest`.

#### P1: Local Gemma ANR (Second Message)
- **LlamaManager**: Pre-prefill cancel check added before entering JNI. If `cancelRequested` is true before `runAppendWithSafeHandler()`, exits cleanly via `Handler.post { onComplete("") }` without touching native code.
- **LlamaManager**: All 12 `withContext(Dispatchers.Main)` calls inside `generateStream()`, `generateWithImage()`, watchdog coroutine, and `generate()` replaced with `android.os.Handler(android.os.Looper.getMainLooper()).post { }`. Lock is no longer held while waiting for Main thread.
- **PromptBudgetLedger**: Thermal throttle (`thermalFactor < 1.0`) now applies only to `generationReserve` (output token count), not to total `nCtx`. Restores `session_primed=true` on subsequent messages, replacing full 636-token cold KV rebuild with 30-token incremental append.

#### P1: Cancel Button Never Reached Native Engine
- **RuntimeBackend**: Added `fun cancelStream() {}` default method to interface.
- **LocalLlamaBackend**: Implemented `override fun cancelStream()` — delegates to `llamaManager.cancelStream()`.
- **HybridOrchestrator**: Added `@Volatile private var activeBackend_` tracking. `cancel()` now calls `activeBackend_.cancelStream()` propagating to `LlamaNative.nativeCancel()`.

### UI/UX

#### Startup Screen
- Removed robot (`SmartToy`) icon and "How can I help you?" greeting from empty chat state.
- Replaced with minimal "A" wordmark in animated concentric ring, app name, and neutral hint text.
- All colors use `MaterialTheme.colorScheme` — adapts to Dark, Light, and AMOLED themes.

#### Thinking Animation
- Replaced previous implementation with three-dot staggered breathing animation.
- 480ms cycle, 160ms inter-dot stagger, scale 0.50→1.00, alpha 0.28→0.88.
- Color: `MaterialTheme.colorScheme.primary` — no hardcoded values.

#### Model Selector Touch Target
- Added `.heightIn(min = 48.dp)` to enforce Material minimum touch target.
- Replaced `.clickable + .pointerInput` with single `.combinedClickable(onClick, onLongClick)`.
- Full pill area is now responsive to tap and long-press.

#### Theme System
- 365 hardcoded `Color.White`, `Color.Black`, and `Color(0xFF…)` occurrences replaced with `MaterialTheme.colorScheme` tokens across 52 UI files.
- Affected: `PlusMenuSheet`, `ActivityFeedComposable`, `VoiceLiveOverlay`, `AgentPlanOverlay`, `AgentPlanContent`, `AgentPlanCard`, `DebugOverlay`, `DebugPanelScreen`, `DebugScreen`, and 43 other screens/components.
- Intentional exception: `StarBackground.kt` — white star particles on AMOLED background (correct for the visual effect).

### Localization

- **Spanish (values-es)**: 90 missing strings added. Key count: 877 → 967 (100% parity with base).
- **Chinese (values-zh)**: 90 missing strings added. Key count: 877 → 967 (100% parity with base).
- All 4 locales (en, ar, es, zh) now have identical key sets. Zero missing, zero extra, zero duplicate keys.

### Compatibility

- **DefaultAssistantManager**: `isMiuiDevice()` now checks `Build.BRAND` for "redmi" and "poco" in addition to `Build.MANUFACTURER`. Falls back to `ro.miui.ui.version.name` and `ro.mi.os.version.name` system properties.
- **DefaultAssistantManager**: Added `isHyperOsDevice()` for Xiaomi HyperOS-specific code paths.

### Documentation

- `docs/FINAL_IMPLEMENTATION_REPORT.md` — detailed per-issue analysis with before/after code
- `docs/VERIFICATION_REPORT.md` — PASS/FAIL table with evidence for every fix
- `UI_REFINEMENT_REPORT.md` — complete UI/UX sprint documentation
- `README.md` — updated with current architecture, execution flow, debugging workflow, and known limitations
- `CHANGELOG.md` — this file

### Known Remaining Issues (Require Hardware)

- **Native cancel latency**: `llama_decode()` 64-token chunks take ~18s on low-end Mediatek. Cancel flag is now set immediately but the running chunk cannot be interrupted. Fix requires editing `LlamaBridge.cpp` to check `g_cancel_requested` inside the decode loop.
- **ANR still possible**: Until the native fix above is applied, cancel during prefill will still cause ANR on slow devices. The Kotlin-layer fixes reduce frequency but do not eliminate the risk.
- **Play Integrity -16**: Cloud project number misconfigured in Play Console. Does not affect inference.
- **nCtx=1536 in BALANCED mode**: Undersized for Gemma 2 2B on 8GB RAM devices.
