# Phase 0 — Risks Register

## Critical (block production)

1. **Confused-deputy risk.** `BIND_ACCESSIBILITY_SERVICE` + remote LLM connectors + tools that fetch web content (`CloudBrowserAgent`) can compose a path where prompt-injection inside fetched HTML drives `AccessibilityCommandBridge` taps and types. Mitigation deferred to Phase 5 (trust boundary tagging + human approval gate for tainted side-effects).
2. **Privacy contradiction.** README states "100% on-device" while the same APK ships Firebase Analytics, Crashlytics, OpenAI/Anthropic/Gemini providers, `CloudBrowserAgent`. Telemetry is consent-gated, but marketing text is materially false. Phase 7 fix.
3. **Play Store policy red flag.** `QUERY_ALL_PACKAGES` permission. Phase 1 will scrub `AndroidManifest.xml` if not actually required by a runtime path.
4. **JNI surface too wide.** ~40 `external fun` in `LlamaNative` over a single `g_ctx`; safe only because every caller routes through `LlamaManager` mutex + single-threaded dispatcher. Any future refactor that bypasses `LlamaManager` corrupts state. Phase 6: split native lib + add JNI-side serialization gate.

## High

5. **`ChatViewModel.kt` is 3,277 LOC** — owns model loading, JNI, hybrid orchestration, paywall, voice transcript, image attachment, runtime diagnostics, retention analytics, multimodal eval, agent state, persistence. A single regression here ripples across the entire UI.
6. **`ServiceLocator` with 50+ singletons** — 17 `executeGraph`-related entries alone. No DI, no scopes, no test substitutability.
7. **DAG runtime unreachable from chat path.** The single largest gap between architecture and runtime call graph.
8. **No persistent agent state across process death.** `SharedPreferencesSnapshotStore` is the only checkpoint; no goal resumption.
9. **`SandboxExecutor` is theatre on Android.** Skill builder UI implies code can be executed; runtime cannot run Python/Node/Kotlin.

## Medium

10. **Two parallel agent stacks compile and increase APK size + cognitive load.**
11. **6 overlapping diagnostics screens.**
12. **3 overlapping voice pipelines.**
13. **`SubAgentRegistry.route` keyword scoring** instead of LLM tool selection — fundamental to why AIRI is a chatbot, not an agent.
14. **No model evaluation harness.** "Production AI agent" is unverifiable without one.

## Low

15. **275 vendored llama.cpp source files** — repo bloat, manual upstream sync. Phase 4 fix (FetchContent).
16. **38 UI screens, ~30 unreachable.** Phase 7 trim.

## Concurrency / lifecycle hazards (Phase 6)

- `LlamaManager.lifecycleLock` correct, but `IncrementalTtsEngine` and `FullDuplexVadEngine` share microphone state without an explicit single-owner audio session — verify in Phase 6.
- Several `ServiceLocator` singletons hold `Context` references without `applicationContext` confirmation.
- `executeGraph`'s parallel-wave executor was never battle-tested because chat path doesn't use it; before reviving in Phase 3, audit thread-pool sizing.
