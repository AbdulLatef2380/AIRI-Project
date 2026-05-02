# AIRI — On-Device Android AI Runtime

## What This Is
AIRI is a production-grade on-device Android AI assistant built with:
- **Kotlin / Jetpack Compose** — UI and coroutine-based inference lifecycle
- **llama.cpp (JNI/C++)** — native LLM inference engine via `LlamaBridge.cpp`
- **Room** — persistent chat history / memory system
- **NDK 25.2.9519653 + CMake 3.22.1** — native build toolchain

This is a **native Android application**. It cannot run as a web server inside Replit.
The canonical build path is GitHub Actions CI (see `.github/workflows/android_build.yml`).
A pre-built debug APK is included at `airi-debug.apk` in the repo root.

---

## Build Requirements
| Tool | Version |
|------|---------|
| JDK | 17 (Temurin) |
| Android SDK | API 34 |
| NDK | 25.2.9519653 |
| CMake | 3.22.1 |
| Build tools | 34.0.0 |
| Gradle | wrapper (`./gradlew`) |

### To build locally (requires Android SDK):
```bash
./gradlew clean assembleDebug \
  -Pandroid.native.buildOutput=verbose \
  --stacktrace
```

### CI build (GitHub Actions):
Push to any branch — `.github/workflows/android_build.yml` runs automatically.
The signed APK artifact is uploaded at the end of the CI job.

---

## Key Source Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/airi/assistant/ai/LlamaManager.kt` | Core inference lifecycle state machine (1867 lines) |
| `app/src/main/cpp/LlamaBridge.cpp` | JNI bridge to llama.cpp (2539 lines) |
| `app/src/main/java/com/airi/assistant/ai/LlamaNative.kt` | JNI declarations |
| `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt` | Main UI ViewModel |
| `app/build.gradle.kts` | Android / CMake / NDK build config |
| `app/src/main/cpp/CMakeLists.txt` | Native build config |

---

## Runtime Stability Architecture

### Three-Layer Serialization
1. **`llamaDispatcher`** — `Dispatchers.IO.limitedParallelism(1)`: single-threaded coroutine dispatcher; no two JNI calls can overlap.
2. **`lifecycleLock` (Kotlin `Mutex`)** — coroutine-level ownership; `generateStream` holds it for the entire lifecycle of one generation.
3. **`LLAMA_LOCK` (C++ `std::mutex`)** — guards the llama.cpp context, sampler, and KV cache at the JNI boundary.

### Session / Generation Ownership
- **`g_session_id`** (native atomic) — bumped by `beginSession()` (hard reset). Captured in Kotlin as `sessionIdAtStart` before every decode loop.
- **`g_generation_id`** (native atomic) — bumped at entry of every `airi_generate_next()` call. Captured in Kotlin as `genIdAtStart` before `generateNextTokens()`. Expected active value while generation N is live: `genIdAtStart + 1`.

Both IDs are validated at **both** Main-dispatch boundary sites (per-batch and tail-flush). A callback is dropped — never reaches the UI — if either ID has moved on.

### State Machine
```
IDLE → PREFLIGHT → PREFILL → GENERATE → COMPLETE/ERROR/CANCELLED → CLEANUP → IDLE
```

### Cancel Paths
- **User cancel**: `cancelStream()` → sets `cancelRequested = true` + calls `LlamaNative.cancel()` + `LlamaNative.nativeCancel()`
- **Watchdog timeout**: sets `cancelRequested = true` + calls both cancel routes (same atomic, dual-call for future-proofing)
- **Context overflow** (status -3): `fullReset()` + retry once with trimmed history
- **Decode error** (status -1): `fullReset()` + surface error to UI
- **Cancelled** (status -2): `invalidateSession()` only (no full reset needed)

### Context Budget
- `MAX_HISTORY_TOKENS = 750` (token budget for history replay)
- `maxHistory = 4` messages (count cap)
- `trimContext()` walks backwards in user/assistant pairs, budgets against live `n_ctx` minus 512-token reserve
- `trimHistory()` is called after every completed generation to keep `chatHistory` within budget

---

## Sampling Parameters (SPEC v4 — fully wired, all 8 params exposed)

All 8 per-generation sampling parameters are now end-to-end from UI slider → native sampler chain:

| Parameter | SharedPrefs key | Default | Range |
|-----------|----------------|---------|-------|
| temperature | `gen_temperature` | 0.7 | 0.01–2.0 |
| top_k | `gen_top_k` | 40 | 0–200 |
| top_p | `gen_top_p` | 0.9 | 0.0–1.0 |
| min_p | `gen_min_p` | 0.05 | 0.0–0.5 |
| repeat_penalty | `gen_repeat_penalty` | 1.1 | 1.0–2.0 |
| presence_penalty | `gen_presence_penalty` | 0.0 | 0.0–2.0 |
| frequency_penalty | `gen_frequency_penalty` | 0.0 | 0.0–2.0 |
| **penalty_last_n** | `gen_penalty_last_n` | **64** | **0–256 (step 16)** |

`penalty_last_n` was added across all 6 layers in this session:
1. `strings.xml` — `R.string.penalty_last_n` ("Repeat Window") + hint string
2. `ModelSettingsScreen.kt` — slider in `AdvancedGenerationSettingsDialog` (0=off label, steps=15 for 17 snap-points at multiples of 16)
3. `ChatViewModel.kt` — reads `gen_penalty_last_n` from SharedPrefs before every `generateStream` call
4. `LlamaManager.kt` — `penaltyLastN: Int = 64` param added to `generateStream`; passed to `nativeSetSamplingParams`; logged in `SAMPLING_PARAMS_PUSHED`
5. `LlamaNative.kt` — `penaltyLastN: Int` added to `external fun nativeSetSamplingParams` declaration
6. `LlamaBridge.cpp` — `jint penaltyLastN` added to JNI entry; writes `g_sp_penalty_last_n` clamped to `[-1, 2048]`

Sampler chain order: `penalties → top_k → top_p → min_p → temperature → dist`.  
Each stage is skipped if its parameter is at its disabled value (e.g. `top_k=0`, `min_p=0.0`, `penalty_last_n=0`).  
`llama_sampler_init_penalties` called with header-confirmed 4-arg signature: `(penalty_last_n, repeat, freq, present)`.

---

## Hybrid Execution Architecture — Production Hardening (Session N)

### Overview
14 new Kotlin files + 6 updated files transforming the Hybrid Execution layer from "architecturally correct" to "production-safe under real load".

### New Files

| Package | File | Purpose |
|---------|------|---------|
| `execution/cloud` | `CloudProviderAdapter.kt` | Interface: streamGenerate + AdapterResult |
| `execution/cloud` | `CloudErrorType.kt` | Normalized error enum (11 types) |
| `execution/cloud` | `CloudErrorMapper.kt` | Pure HTTP→CloudErrorType mapper + retryability |
| `execution/cloud` | `RetryPolicy.kt` | Exponential back-off with ±25% jitter (AWS/Google spec) |
| `execution/cloud` | `GeminiAdapter.kt` | Real Gemini v1beta SSE (generativelanguage.googleapis.com) |
| `execution/cloud` | `OpenAIAdapter.kt` | OpenAI + Kimi + Custom (stream_options.include_usage=true) |
| `execution/cloud` | `OpenRouterAdapter.kt` | OpenRouter (HTTP-Referer + X-Title headers) |
| `execution/cloud` | `AnthropicAdapter.kt` | Anthropic Messages API SSE (anthropic-version: 2023-06-01) |
| `execution/cloud` | `CloudAdapterFactory.kt` | Provider→adapter selection; legacy key migration |
| `execution/network` | `NetworkGuard.kt` | Absolute LOCAL_ONLY/MAXIMUM-privacy firewall (last line before HTTP) |
| `execution/network` | `ConnectivityMonitor.kt` | StateFlow<Boolean> via ConnectivityManager.NetworkCallback |
| `execution/security` | `SecureApiKeyStore.kt` | Encrypted API key vault wrapping SecureStorage (AES256-GCM) |
| `execution/accounting` | `TokenAccountant.kt` | Accurate per-provider daily token tracking with StateFlow |
| `execution/diagnostics` | `ExecutionDiagnosticsState.kt` | Live diagnostics snapshot + ExecTransitionEvent ring buffer |

### Updated Files

| File | Change |
|------|--------|
| `execution/backend/CloudBackend.kt` | Full rewrite: adapters + NetworkGuard + RetryPolicy; context+prefs constructor |
| `execution/HybridOrchestrator.kt` | Full rewrite: Mutex ownership gate, generation counter, deterministic failover, PrivacyGuard integration, live diagnostics |
| `execution/router/RoutingPolicy.kt` | +requiresOffline Rule 4, +explicit no-network Rule 11; rule renumbering |
| `ai/remote/RemoteModelExecutor.kt` | +`@Volatile activeConnection`, +`cancelCurrentRequest()`, +`ensureActive()`, +retry backoff |
| `core/debug/RuntimeDiagnosticsState.kt` | +10 `execXxx` fields for execution layer observability |
| `ui/viewmodel/ChatViewModel.kt` | CloudBackend(prefs,ctx) constructor; +hybridOrchestrator, +tokenAccountant, +secureApiKeyStore, +execDiagnostics StateFlow; onCleared cancel |

### Architecture Contracts

**Execution ownership:** `HybridOrchestrator.executeStream` is serialized by a `kotlinx.coroutines.sync.Mutex`. One active stream at a time. Stale tokens dropped via `AtomicLong` generation counter CAS.

**Network firewall layers (3 redundant):**
1. `RoutingPolicy` — never selects cloud when LOCAL_ONLY/MAXIMUM/no-internet
2. `PrivacyGuard` — sanitizes or blocks before HTTP dial
3. `NetworkGuard` — last-line absolute veto before `HttpURLConnection.connect()`

**API key security:** All keys stored in `EncryptedSharedPreferences` (AES256-GCM) via `SecureApiKeyStore → SecureStorage`. Legacy plaintext keys from `RemoteModelRegistry` auto-migrate on first cloud call.

**Cancellation contract:** `HybridOrchestrator.cancel()` + `remoteExecutor.cancelCurrentRequest()` both called in `ChatViewModel.onCleared()`. Every adapter calls `conn.disconnect()` in a `finally` block. `ensureActive()` checked per-token in all streaming loops.

**Token accounting:** Accurate counts from provider `usage` fields (not estimates). Persisted to `airi_token_accounting` SharedPrefs. Resets at midnight UTC. Exposes `StateFlow<Map<CloudProvider, ProviderStats>>` to UI.

---

## Production Hardening — Session Audit Findings

Comprehensive audit of 15+ subsystems performed in this session. All critical paths verified:

| System | Status | Notes |
|--------|--------|-------|
| Inference state machine | ✅ | Status-driven (-1/-2/-3) routing; each branch hard-clears buffers independently |
| Stale-token guard | ✅ | genIdAtStart+1 + sessionId checked at both per-batch and tail-dispatch sites |
| Watchdog | ✅ | Runs on Dispatchers.Default (not llamaDispatcher); inactivity-based not wall-clock |
| lifecycleLock | ✅ | Kotlin Mutex (suspend-boundary safe); cancel paths are intentionally lock-free |
| Cancel chain | ✅ | cancelRequested.set(false) + nativeClearCancel() before every turn |
| fullReset | ✅ | cancel → nativeFullReset → invalidateSession → cancelRequested.set(false) |
| History trimming | ✅ | trimHistoryByTokens (token-budget MAX_HISTORY_TOKENS=750); primedHistory drift detection |
| KV reconcile | ✅ | Per-message replay; PREFLIGHT_OVERFLOW → fullReset → retry-once |
| VoskEngine lifecycle | ✅ | stopRequested flag; AudioRecord.release() called from onFinal/onError; structured concurrency |
| VoiceManager STT | ✅ | sttScope = SupervisorJob+IO; model load off-main via withContext(IO) in loadActiveModel |
| TTS streaming | ✅ | Sentence-boundary flush (. ! ? ؟ ، \\n); QUEUE_ADD; ttsStreamFlush on turn end |
| Voice state machine | ✅ | voiceStateRef ↔ voiceState sync guarded; liveChatActiveRef loop with re-arm tick |
| Speculative decoding | ✅ | SpeculativeManager SharedPrefs-controlled; isDraftLoaded() checked before use |
| UTF-8 guard (native) | ✅ | is_valid_utf8() checked before every NewStringUTF; trailing bytes held until complete |
| loadModel | ✅ | Prior g_ctx/g_model freed before every load; mmap=true, mlock=false |

**Production bug fixed — Voice auto-stop timer (`ChatScreen.kt`):**  
The 7-second LISTENING timeout previously set `voiceState = IDLE` without calling `stopInAppStt()`.  
The `VoskEngine`'s `AudioRecord` capture loop kept running with the microphone open — wasting battery  
and able to fire a stale `onFinal` into the wrong state-machine phase.  
Fix: `stopInAppStt()` is now called before `voiceState = IDLE` in the auto-stop `LaunchedEffect`.

---

---

## Product Transformation — UX Overhaul (Session 3)

### Theme System
- **Color.kt** — Expanded from 2 tokens to a full semantic token set: surface elevation ladder (`SurfaceBase/Raised/Floating/Highlight`), semantic colors (`SemanticSuccess/Error/Warn`), glass surface tokens (`GlassWhite/WhiteBorder`), dedicated bubble colors (`UserBubbleSurface #162C45`, `AiBubbleSurface #101624`, `AiBubbleBorder`)
- **Theme.kt** — Added complete `AIRITypography` (`Typography`) covering all 12 Material3 type roles from `displaySmall` to `labelSmall` with proper font weights, sizes, and letter spacing

### ChatScreen.kt — 13 targeted transformations
1. **Imports** — `LocalHapticFeedback`, `HapticFeedbackType`, and 4 new color token imports added
2. **imePadding** — `bottomBar` Column now has `.imePadding()` so input bar rides above keyboard
3. **`ChatMessageList` call site** — `onSuggestionClick = { viewModel.sendMessage(it) }` wired
4. **`ChatMessageList` function** — Added `onSuggestionClick` param; empty state now shows pulsing radial-glow avatar icon (breathing animation), centered AIRI title/subtitle, and 4 tappable suggestion chips in 2×2 grid when model is ready; LazyColumn wrapped in Box with scroll-to-bottom FAB
5. **`UserBubble`** — Entry slide-in animation (right→center + fade, 220ms FastOutSlow); `widthIn(max=340dp)` up from 300dp; flat `UserBubbleSurface` fill replaces gradient; corner radius 20/4/20/20dp; tap-to-copy with `HapticFeedbackType.LongPress`; text 15sp/23sp line height
6. **`AiBubble`** — Removed long-press `DropdownMenu` and `TextButton` share; replaced with always-visible compact 3-button inline action row (Copy + Speak + Share, 32dp icon buttons, 15dp icons, 38% alpha tint); outer Row gets `padding(end=44dp)` so bubbles fill width naturally; `AiBubbleSurface/Border` colors; corner radius 4/20/20/20dp; text 15sp/23sp; avatar upgraded to 30dp radial gradient glow; haptic on copy/speak/share
7. **`AiStreamingBubble`** — Matches AiBubble style: outer Row `padding(end=44dp)`, `fillMaxWidth()` Column, `AiBubbleSurface`, 30dp radial avatar, corner 4/20/20/20dp, text 15sp/23sp, accent border during streaming
8. **`BlinkingCursor`** — Replaced snap-to ▋ with `AnimatedContent` crossfade (80ms); uses accent-colored `▍`; blink period 500ms
9. **`AiriThinkingPulse`** — Dots now animate both `alpha` (0.20→1.0) and `scale` (0.70→1.0) via dual `animateFloat` loops with staggered `StartOffset`; dots 7dp (was 6dp)
10. **Voice indicator** — Replaced colored dot + text with `VoiceWaveformBars` (5 staggered animated bars) + label text; bar heights animate at different rates for organic feel
11. **`VoiceWaveformBars`** (new composable) — 5 bars, staggered heights (10/18/10dp peaks), `StartOffset` stagger, color-coded by voice state, 280–560ms animation cycle
12. **`ScrollToBottomFab`** (new composable) — 38dp circle, spring-bounce enter animation (`DampingRatioMediumBouncy`), accent color, `KeyboardArrowDown` icon; appears when `firstVisibleItemIndex > 1`
13. **`strings.xml`** — 4 suggestion chip strings added: `suggestion_what_can_you_do/explain_ai/write_poem/brainstorm`

---

## Product Transformation — Markdown + Performance Session 4

### New File: `ui/util/MarkdownText.kt` (215 lines, zero external deps)
Pure-Compose streaming-safe Markdown renderer using only `AnnotatedString`:
- Block-level: `# H1 / ## H2 / ### H3`, `- bullet / * / +`, `1. ordered`, ```` ``` ```` fenced code blocks, `---` horizontal rules, blank-line paragraph breaks
- Inline: `**bold** / __bold__`, `*italic* / _italic_`, `` `code` ``, `~~strikethrough~~`
- Streaming safety: all unclosed delimiters emit as literal text — never corrupts mid-stream partial responses
- Performance: all parsing wrapped in `remember(rawText)` — zero work on recompositions that don't change the text
- Code blocks render in `FontFamily.Monospace` on a dark `#0D1118` background with `#79C0FF` token color
- Switched to `textColor` prop + `graphicsLayer` for alpha, eliminating per-frame `Color.copy(alpha=…)` allocation

### ChatScreen.kt — 7 additional targeted changes

| # | What | Effect |
|---|---|---|
| 1 | `AiBubble` now renders via `MarkdownText` | Bold, italic, code, bullets, headings render in all finished AI responses |
| 2 | `reversedMessages = remember(messages) { messages.reversed() }` | List reversal runs once per message-list change, not on every streaming token (O(n) → O(1) during streaming) |
| 3 | `itemsIndexed(reversedMessages, ...)` | Uses pre-reversed list; both LazyColumn and the hideAvatar lookup reference same instance |
| 4 | Scroll-to-bottom observer converted to `snapshotFlow { streamingText.length }` | Single long-lived coroutine; eliminates O(tokens) coroutine cancel+restart churn during streaming |
| 5 | TTS streaming observer converted to `snapshotFlow { streamingText }` | Same elimination; `LaunchedEffect(streamingText, ...)` was restarting on every token |
| 6 | `AiriThinkingPulse` — `color.copy(alpha=alpha)` → `graphicsLayer { alpha = alphaPct }` + solid base color | Eliminates Color object allocation on every animation frame |
| 7 | `VoiceWaveformBars` — same graphicsLayer alpha fix + `barAlpha` hoisted out of per-bar loop | Zero per-frame Color alloc; single InfiniteTransition drives all 5 bars under one Choreographer callback |
| 8 | `ScrollToBottomFab` — removed `.shadow(12.dp, …ambientColor=CosmicAccent…)` | Colored shadows force GPU compositing layer; removed since FAB is only visible mid-scroll |

### ChatViewModel.kt — StringBuilder accumulator (hot-path, high impact)
- Added `private val streamAccumulator = StringBuilder(1024)` class-level field
- Replaced `_streamingText.update { current -> current + tokenBatch }` with `streamAccumulator.append(tokenBatch); _streamingText.value = streamAccumulator.toString()`
- Previous approach: O(n²) total — each of N tokens copied the entire response string (T chars), costing T × N bytes of allocation
- New approach: O(n) total — StringBuilder uses internal char array doubling; `toString()` is one final copy
- All 8 clear sites (`_streamingText.value = ""`) now also call `streamAccumulator.setLength(0)` to stay in sync

### VoiceManager.kt — TTS streaming latency
- Added **length-based flush trigger**: when buffer reaches ≥80 chars with no punctuation found, flushes at last word boundary before char 80
- Reduces first-speech latency for unpunctuated passages (code explanations, long bullet intros) from "full-sentence wait" to ~10-word chunks
- Added Latin comma `,` to the punctuation set (was only `.!?؟،\n`) — catches prose like "First, we need to…"

---

---

## Phase 3 — Full-Duplex VAD Interruption System (ChatGPT-class barge-in)

### Overview
Zero-cloud, on-device VAD interruption system. The user can speak while the AI is talking and AIRI immediately stops TTS and starts listening. Powered by Silero VAD (ONNX, ~2 MB) via `android-vad-silero 2.1.3`.

### New File: `voice/FullDuplexVadEngine.kt`

Production-grade Silero VAD engine with 7 concurrent engineering issues addressed:

| Issue | Problem | Fix |
|-------|---------|-----|
| 1. Dual Audio Ownership | VAD mic (VOICE_COMMUNICATION) and Vosk mic (VOICE_RECOGNITION) race for hardware | `stop()` releases `AudioRecord` **synchronously** before returning. Caller gets the mic free instantly. |
| 2. State Machine Race | TTS end racing against VAD detection: both try to fire `onSpeakingDone` | `AtomicReference<FullDuplexVadEngine>` identity check in callback + `AtomicBoolean` CAS gate in VoiceManager |
| 3. Double Interrupt | Consecutive Silero "speech" frames trigger multiple `onVoiceDetected` | `detected.compareAndSet(false, true)` — first frame wins, all subsequent dropped |
| 4. TTS Chunk Overlap | `onDone` fires for each chunk; can trigger `onSpeakingDone` prematurely | `onDone` now checks BOTH `utteranceId == last` AND `!ttsStreamActive`; `ttsStreamFlush()` always queues sentinel `"\u200B"` so `lastQueuedUtteranceId` always resolves |
| 5. Memory Leak | `AudioRecord` not released if coroutine cancelled | `finally` block always releases; `stop()` synchronous release is also a safety net |
| 6. Lifecycle Gap | App backgrounded → mic ghost lock (Android 14/15) | `LifecycleEventObserver` on `ON_PAUSE` stops all audio subsystems and resets loop |
| 7. UX Gap | No visual feedback in the ~50ms between VAD detection and STT start | `isVadInterrupting` state → amber "Interrupting…" glow in waveform banner |

### Audio Source Decision (battle-tested via GitHub research)
- **VAD uses `VOICE_COMMUNICATION`** — activates hardware AEC DSP pipeline. TTS speaker output is cancelled from the mic signal before Silero ever sees it. Prevents false triggers from the AI's own voice.
- **`AcousticEchoCanceler`** additionally attached to session ID for software-layer AEC on devices without hardware AEC.
- **10-frame warmup** (200ms) for ambient noise floor calibration before arming.
- `VERY_AGGRESSIVE` Silero mode + `speechDurationMs=80ms` (4 frames): high noise rejection, ~20ms detection latency.

### Interrupt Ordering (critical for audio-source exclusivity)
```
VAD detects speech
  → Step 1: thisEngine.stop()         ← VOICE_COMMUNICATION AudioRecord released synchronously
  → Step 2: tts.stop()                ← TTS playback halted
  → Step 3: listener.onVadInterrupted() ← ChatScreen bumps tick
  → LaunchedEffect fires
  → startInAppStt()                   ← VoskEngine opens VOICE_RECOGNITION (mic guaranteed free)
```

### Dual CAS Guard (prevents all race conditions)
```kotlin
// Guard 1 — per-session gate (vadInterruptFired in VoiceManager):
if (!vadInterruptFired.compareAndSet(false, true)) return  // double-fire dropped

// Guard 2 — engine identity check (vadEngineRef in VoiceManager):
if (!vadEngineRef.compareAndSet(thisEngine, null)) return  // stale callback dropped
```

### Visual States
| `isVadInterrupting` | `voiceState` | Banner | Color |
|----|----|----|-----|
| false | IDLE | hidden | — |
| false | LISTENING | "Listening…" | Coral `#FF6B6B` |
| **true** | LISTENING | **"Interrupting…"** | **Amber `#FFB347`** |
| false | SPEAKING | "Speaking…" | Sky `#4FC3F7` |
| false | PROCESSING | "Processing…" | CosmicAccent |

### Key Changes Per File
- **`FullDuplexVadEngine.kt`** — Complete rewrite (v3): synchronous `stop()`, AEC, noise floor warmup, CAS detected, VOICE_COMMUNICATION source
- **`VoiceManager.kt`** — Complete rewrite (v3): `AtomicReference<FullDuplexVadEngine?>`, dual CAS gate, sentinel in `ttsStreamFlush()`, `!ttsStreamActive` condition in `onDone`, `stopVadIfRunning()` public API
- **`ChatScreen.kt`** — `isVadInterrupting` state, `LifecycleEventObserver`, `stopVadIfRunning()` at all 4 manual stop sites, amber glow wired into `ChatInputBar`
- **`ChatInputBar`** — `isVadInterrupting: Boolean` param, amber waveform color + "Interrupting…" label, bars animate during interrupt
- **`libs.versions.toml`** — `lifecycle-runtime-compose` added (provides `LocalLifecycleOwner` for Compose)
- **`app/build.gradle.kts`** — `lifecycle-runtime-compose` implementation added

### Dependencies Added This Phase
| Library | Version | Purpose |
|---------|---------|---------|
| `io.github.gkonovalov:android-vad-silero` | 2.1.3 | Silero DNN VAD (ONNX, on-device) |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.7.0 | `LocalLifecycleOwner` for `DisposableEffect` lifecycle observer |

---

---

## Omega Core — Runtime Integrity Fixes (Session 5)

Full audit of `LlamaManager.kt` (1903 lines), `LlamaBridge.cpp` (2638 lines), `ChatViewModel.kt` (2190 lines), `LlamaNative.kt` (262 lines) completed. Five confirmed bugs fixed:

### BUG-1 (CRITICAL) — Speculative generation tokens silently dropped
**File:** `LlamaBridge.cpp` — `generateNextTokensSpeculative()`  
**Root cause:** `airi_generate_next()` bumps `g_generation_id` at entry. The speculative fast-path (when the draft model is usable and in sync) runs its own decode loop and never called `g_generation_id.fetch_add(1)`. The Kotlin stale-callback guard checks `genIdOnMain == genIdAtStart + 1` at every Main-dispatch site; since the ID was never bumped, the check always failed and every speculative token was dropped. The user saw blank output whenever speculative decoding was active.  
**Fix:** Added `g_generation_id.fetch_add(1) + 1` + `PROOF("GENERATION_ID_BUMP … via=speculative")` immediately after the phase/status setup in the speculative fast-path.

### BUG-2 (HIGH) — Missing SPEC v3 cancel-at-entry guard in speculative path
**File:** `LlamaBridge.cpp` — `generateNextTokensSpeculative()`  
**Root cause:** If `nativeCancel()` was called in the window between prefill completing and the speculative path entry, the unconditional `g_cancel.store(false)` at the top of the speculative fast-path silently discarded the pending cancel request. The standard path (`airi_generate_next`) had already been hardened with a cancel-at-entry check (SPEC v3), but the speculative path was missed.  
**Fix:** Added the same guard: `if (g_cancel_requested.load()) { g_last_gen_status.store(-2); PROOF(…); return; }` before the `g_cancel.store(false)` — identical to the standard path.

### BUG-3 (MEDIUM) — Stale cancel flag and status survive model swap
**File:** `LlamaBridge.cpp` — `loadModel()` and `loadModelWithProgress()`  
**Root cause:** When a new GGUF is loaded, `g_n_past` was reset to 0 but `g_cancel_requested` and `g_last_gen_status` were not cleared. A stale `-2` in `g_last_gen_status` would be read by `nativeGetLastStatus()` on the very first turn after a model swap and falsely route it through `fullReset()` + `onError`. A stale `true` in `g_cancel_requested` would poison the incremental-session path on the first turn (which skips `beginSession()` and therefore skips its implicit cancel clear).  
`g_draft_in_sync` could also still be `true` from the previous session while `g_draft_n_past` was nonzero and `g_n_past` was 0, causing speculative path logic errors.  
**Fix:** Added `g_cancel_requested.store(false); g_last_gen_status.store(0); airi_draft_clear_kv();` in both `loadModel()` and `loadModelWithProgress()` immediately after `g_n_past = 0;`, with a detailed comment explaining each field's risk.

### BUG-4 (MEDIUM) — `applyRuntimeMode()` bypasses `lifecycleLock`
**File:** `LlamaManager.kt` — `applyRuntimeMode()`  
**Root cause:** `applyRuntimeMode()` called `setRuntimeMode()` + `invalidateSession()` on the `llamaDispatcher` coroutine scope without entering `lifecycleLock`. The single-threaded dispatcher serializes it in practice, but the Kotlin Mutex is the contractual ownership boundary for the inference lifecycle. Any future refactor introducing a second dispatcher (e.g. a prefetch lane) would create a race window where KV teardown and active generation overlap.  
**Fix:** Wrapped the entire body of the `scope.launch` in `lifecycleLock.withLock { … }`, matching the pattern used by `generateStream` and `fullReset`.

### BUG-5 (LOW) — Dead `stallCallback` field
**File:** `LlamaManager.kt`  
**Root cause:** `@Volatile private var stallCallback: (() -> Unit)? = null` was never written after declaration and never read. Stall warnings are routed directly via the `onStallWarning` lambda captured per `generateStream` call; this field was a leftover from an earlier design.  
**Fix:** Field removed entirely.

---

---

## RuntimeSupervisor — Thermal / Memory Pressure Watchdog

**New file:** `app/src/main/java/com/airi/assistant/ai/RuntimeSupervisor.kt`

Polls Android thermal status (API 29+) and available RAM every 15 seconds and automatically downgrades the active `PerformanceMode` when sustained device pressure is detected.

### Design Invariants
| Property | Value |
|----------|-------|
| Poll interval | 15 000 ms |
| Confirmation cycles | 2 (≈30 s sustained pressure before action) |
| Auto-upgrade | **Never** — only downgrades autonomously |
| User ceiling | Always applies `worstOf(supervisorRecommend, userMode)` — supervisor cannot grant MORE resources than the user chose |
| Thread safety | Runs on `Dispatchers.Default`; mode changes dispatched to `llamaDispatcher` via `applyRuntimeMode` — serialized behind any in-flight decode |

### Thermal Thresholds (Android API 29+ `PowerManager.THERMAL_STATUS_*`)
| Status value | Mapped mode |
|---|---|
| 0–1 (NONE / LIGHT) | No restriction |
| 2 (MODERATE) | Cap at BALANCED |
| ≥ 3 (SEVERE / CRITICAL / EMERGENCY) | Force FAST |
| Pre-API-29 device | No restriction (graceful fallback) |

### Memory Thresholds
| Available RAM | Mapped mode |
|---|---|
| `ActivityManager.lowMemory = true` OR < 300 MB | Force FAST |
| 300–600 MB | Cap at BALANCED |
| ≥ 600 MB | No restriction |

### Observable Log Tags (AIRI_PROOF)
| Tag | Meaning |
|-----|---------|
| `SUPERVISOR_START` | Polling loop armed, parameters logged |
| `SUPERVISOR_THERMAL status=N` | Raw `PowerManager.currentThermalStatus` reading |
| `SUPERVISOR_MEMORY avail_mb=N low_mem=B` | Raw `ActivityManager.MemoryInfo` reading |
| `SUPERVISOR_PRESSURE_DETECTED mode=X` | First observation of a restrictive mode |
| `SUPERVISOR_PRESSURE_CONFIRM cycle=N/2` | Accumulating confirmation |
| `SUPERVISOR_MODE_CHANGE from=X to=Y reason=Z` | Mode change committed and applied |
| `SUPERVISOR_PRESSURE_RESOLVED` | Pressure subsided before threshold — no action |
| `SUPERVISOR_OVERRIDE mode=X reason=Z` | ViewModel received and applied the override |
| `SUPERVISOR_STOP` | Polling cancelled (ViewModel.onCleared) |

### Integration points in `ChatViewModel.kt`
- **Field declaration** (line ~185): `private val runtimeSupervisor = RuntimeSupervisor(…)` constructed alongside `llamaManager`.
- **`loadModel()` success branch**: `runtimeSupervisor.stop(); runtimeSupervisor.start()` — idempotent, handles model-swap.
- **`onCleared()`**: `runtimeSupervisor.stop()` — always called before ViewModel destruction.

---

## Hybrid Execution Architecture (Session — Final)

Multi-runtime AI operating layer. Three execution modes, capability-aware routing, privacy guard, origin tagging, and full user control UI.

### Execution Package: `com.airi.assistant.execution`

| File | Purpose |
|------|---------|
| `ExecutionMode.kt` | `ExecutionMode` (LOCAL_ONLY/CLOUD_ONLY/HYBRID), `PrivacyLevel` (STANDARD/ENHANCED/MAXIMUM), `CloudProvider` (OPENAI/ANTHROPIC/GOOGLE/CUSTOM), `ExecOrigin` (NONE/LOCAL/CLOUD/HYBRID) enums |
| `CapabilityProfile.kt` | `ModelCapabilities`-backed runtime capability descriptor; `contextWindow`, `toolCalling`, `imageUnderstanding`, `multiLanguage`, `parameterCount`, `supportsStreaming` |
| `ExecutionRequest.kt` | Per-turn request envelope: prompt, queryType, attachments, requiredCapabilities, timeoutMs |
| `ExecutionResult.kt` | Per-turn result envelope: text, origin, latencyMs, tokensUsed, error |
| `HybridOrchestrator.kt` | Top-level entry point; calls `RuntimeRouter`, dispatches to backend(s), applies HYBRID fan-out, returns tagged `ExecutionResult` |
| `prefs/ExecModePreferences.kt` | SharedPrefs-backed user settings: mode, privacy, provider, token budget, offline fallback, internet permission |
| `privacy/PrivacyGuard.kt` | Pre-flight PII check; sanitizes prompts before cloud dispatch; blocks cloud in MAXIMUM privacy mode |
| `privacy/SanitizationResult.kt` | Sanitization outcome (ALLOWED/SANITIZED/BLOCKED) with redacted prompt |
| `router/DeviceSignals.kt` | Snapshot of on-device runtime signals: battery, thermal, RAM, network, model loaded |
| `router/RoutingPolicy.kt` | Pure-function rules engine: `decide()` returns `List<RuntimeBackend>` based on mode, signals, capabilities, privacy |
| `router/RuntimeRouter.kt` | Collects `DeviceSignals` + `ExecModePreferences`, calls `RoutingPolicy`, returns `RoutingDecision` |
| `backend/RuntimeBackend.kt` | Interface: `isAvailable`, `capabilities`, `execute(request)` |
| `backend/LocalLlamaBackend.kt` | Wraps `LlamaManager.generateStream` via `LlamaNative`; detects capabilities via `ModelCapabilities.detect()` |
| `backend/CloudBackend.kt` | Wraps existing `RemoteApiConnector`-style streaming; routes by `CloudProvider` preference |

### Routing Logic (RoutingPolicy)

| Mode | Policy |
|------|--------|
| `LOCAL_ONLY` | Always returns `[local]`. Falls back to `[]` if model not loaded. |
| `CLOUD_ONLY` | Returns `[cloud]` if internet granted + privacy allows. Else `[]`. |
| `HYBRID` | Tries local first if device is strong. Routes cloud for deep-query types (ANALYTICAL/CREATIVE) or when local is weak. Falls back gracefully. |

### Privacy Guard

| Privacy Level | Behavior |
|---|---|
| `STANDARD` | No PII stripping; cloud dispatch allowed |
| `ENHANCED` | PII patterns redacted (email, phone, SSN, card numbers) before cloud dispatch |
| `MAXIMUM` | Cloud dispatch fully blocked; all requests forced to local |

### ChatViewModel Integration Points

| Location | What was wired |
|---|---|
| Fields (~line 292) | `execModePrefs`, `localLlamaBackend`, `cloudBackend`, `runtimeRouter`, `_executionMode`, `_lastExecOrigin` |
| LOCAL_ONLY branch | Routes through `HybridOrchestrator`, tags `ExecOrigin.LOCAL` |
| CLOUD_ONLY branch | Routes through `HybridOrchestrator`, tags `ExecOrigin.CLOUD` |
| HYBRID branch | Tries local; falls back to cloud; tags origin accordingly |
| `streamRemoteResponse` | Tags `_lastExecOrigin.value = ExecOrigin.CLOUD` before first token |
| `ChatMessage` | Added `execOrigin: ExecOrigin = ExecOrigin.NONE` field |
| `setExecutionMode()` | Public method — persists + updates StateFlow |
| `setPrivacyLevel()` | Public method — persists |
| `grantInternetPermission()` | Public method — persists |
| `getExecModePrefs()` | Public accessor for UI panels |

### UI Components

**`ExecutionModePanel.kt`** — Full user control panel embedded in both `SettingsScreen` (after `ApiKeysSection`) and `PerformanceScreen` (after `RuntimeWarningsPanel`):
- 3-way mode toggle: LOCAL / HYBRID / CLOUD with icon badges
- Privacy level picker: STANDARD / ENHANCED / MAXIMUM
- Internet permission toggle (required for CLOUD/HYBRID)
- Offline fallback toggle
- Cloud provider selector (OpenAI / Anthropic / Google / Custom)
- Daily cloud token budget display with progress bar

**`ExecOriginBadge.kt`** — Compact per-message badge showing `🖥 LOCAL`, `☁ CLOUD`, or `⚡ HYBRID` with appropriate tint. Visible on all assistant messages where `execOrigin != NONE`.

**`AiBubble` in `ChatScreen.kt`** — Added `execOrigin` parameter; renders `ExecOriginBadge` below the message footer when `execOrigin.isVisible`.

---

## Known Gaps / Future Work
- Draft model speculative decoding path (`SpeculativeManager`) is wired but requires a companion draft GGUF to activate.
- Accessibility: TalkBack labels for voice state indicator and streaming progress are not set.
- `AiBubble` inline action "Copy" shows no visual confirmation toast (snackbar not yet wired to copy action).
- VAD adaptive noise threshold: currently logs ambient RMS but does not dynamically adjust `speechDurationMs`. Future: increase durationMs in noisy environments.
- VAD `MAX_SESSION_MS = 50s` hard timeout: protects against eternal mic lock but means extremely long AI monologues (>50s) lose VAD coverage for the tail. Future: restart VAD at the 45s mark.
- Hybrid execution token budget tracking (`cloudTokensUsedToday`) currently only incremented manually; future: hook into `CloudBackend.execute()` to count tokens automatically.
