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

## Known Gaps / Future Work
- `RuntimeSupervisor` (thermal / memory pressure monitoring) is not yet implemented.
- Draft model speculative decoding path (`SpeculativeManager`) is wired but requires a companion draft GGUF to activate.
- Accessibility: TalkBack labels for voice state indicator and streaming progress are not set.
- Markdown rendering in AI bubbles not yet implemented (plain text only).
- `AiBubble` inline action "Copy" shows no visual confirmation toast (snackbar not yet wired to copy action).
