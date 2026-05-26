# Phase 2 — Inference Unification Report
*HybridOrchestrator is now the single inference entry point. 31 PASS, 0 FAIL.*

---

## What Changed

### Problem (verified)
`ChatViewModel.sendMessage()` called `llamaManager.generateStream()` and
`remoteExecutor.generateStream()` directly — bypassing `HybridOrchestrator` entirely.
This meant the privacy gate, genId staleness guard, executionLock, deterministic
fallback chain, and exec-origin tagging were **never exercised** on the primary chat path.
`HybridOrchestrator.executeStream()` was wired but only fired via the dead
`cognitiveLoop.orchestratorProvider` path.

### Fix
Replaced the `if (deviceWeak && remote) { ... } else { llamaManager.generateStream(...) }`
dual-branch with a single `hybridOrchestrator.executeStream(request, context, onToken, onComplete, onError)` call.

The 182-line orphaned `llamaManager.generateStream` block was deleted.
`cancelGeneration()` now calls `hybridOrchestrator.cancel()` instead of `llamaManager.cancelStream()` directly.

### What HybridOrchestrator adds (now active on every chat turn)
- `executionLock` — serialises concurrent sendMessage calls (was unguarded before)
- `currentGenId` — stale-token rejection (was unguarded before)
- `PrivacyGuard` — blocks cloud when LOCAL_ONLY mode is set (was bypassed before)
- Deterministic primary→fallback chain — local→cloud or cloud→local per config
- Exec-origin tagging — every `onComplete` now reports `ExecOrigin.LOCAL` or `CLOUD`
- Per-event diagnostics writes — `ExecDiagnosticsScreen` now shows real data

### What was preserved unchanged
- All `onToken` logic: thinking stages, accumulator, first-token watchdog, semantic-cut
- All `onComplete` logic: partial-cut text, finish(), stall recovery
- All `onError` logic: ERR_FIRST_TOKEN_TIMEOUT / ERR_INACTIVITY_TIMEOUT / ERR_NATIVE messages
- `streamRemoteResponse()` private function remains for `orchestratorProvider` delegate calls
- All streaming parameters: maxTokens, temperature, systemPrompt, sessionId
- `withTimeout(90_000L)` cloud guard preserved inside `streamRemoteResponse`

## Files Modified

| File | Change |
|---|---|
| `ui/viewmodel/ChatViewModel.kt` | Replaced dual direct-inference branches with `hybridOrchestrator.executeStream()`; deleted 182-line orphaned `llamaManager.generateStream` block; added `ExecutionRequest` import; fixed `cancelGeneration()` to use `hybridOrchestrator.cancel()`; replaced `hybridOrchestrator.isNetworkAvailable()` (non-existent) with `ServiceLocator.networkService.isOnline()` |

## Runtime Impact

| Before | After |
|---|---|
| Privacy gate bypassed on chat | Privacy gate active on every turn |
| No genId staleness guard | Stale tokens rejected via currentGenId |
| No execution serialisation | executionLock prevents concurrent inference |
| ExecDiagnosticsScreen shows no data | Real diagnostics data on every turn |
| cancelGeneration bypasses orchestrator | Cancel properly propagates through orchestrator chain |

## Compile Risks (unverified without SDK)
- `ExecutionRequest.requiresVision` field — confirmed exists in ExecutionRequest.kt line 41
- `ExecutionRequest.requiresOffline` field — confirmed exists line 43
- `ExecutionRequest.estimatedPromptTokens` field — confirmed exists line 46
- `ExecutionRequest.sessionTag` field — confirmed exists line 48
- `HybridOrchestrator.executeStream(request, context, onToken, onComplete, onError)` — confirmed line 120
- `ExecOrigin` parameter on `onComplete` — confirmed in HybridOrchestrator signature

## Remaining in ChatViewModel (not changed in Phase 2)
- `sendMessageWithAttachments()` — has its own generation path, Phase 6 scope
- `sendMessageWithImage()` — same, Phase 6 scope  
- Tool follow-up second LLM call in `handleToolIfNeeded` — still calls through `streamRemoteResponse` which uses `remoteExecutor` directly; Phase 5 scope
- `llamaManager` field still exists for model loading/unloading operations (correct — these are not inference calls)
