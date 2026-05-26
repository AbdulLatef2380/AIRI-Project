# UCL.executeGraph() Migration Plan
*Phase 2 Pre-Audit — verified against actual source before any implementation.*

---

## 1. Current Execution Sequence (verified)

```
User input → ChatViewModel.sendMessage()                    [Main thread]
  │
  ├─ QueryClassifier.classifyQuery(input)                   [synchronous, main thread]
  │   → QueryType ∈ {SIMPLE, ANALYTICAL, ACTION, CREATIVE, UNKNOWN}
  │   ACTION triggers when: input starts with "send/write/implement/create/build/
  │   set up/configure/install/run/execute/open/generate/produce/draft/code/
  │   program/design/deploy/fix" or Arabic equivalents
  │
  ├─ PolicyEngine + CreditMeteringEngine gates              [synchronous, main thread]
  │
  └─ viewModelScope.launch {                                [coroutine: Dispatchers.Default (viewModelScope)]
        ├─ memoryManager.loadSession()                      [suspending IO]
        ├─ RagRetriever.buildContextBlock()                 [suspending IO]
        │
        ├─ [LOCAL PATH] LlamaManager.generateStream()       [suspending on llamaDispatcher (1 thread)]
        │   └─ JNI: llama_decode() loop                    [native, blocking on llamaDispatcher]
        │
        ├─ [CLOUD PATH] streamRemoteResponse()              [suspending IO with withTimeout(90_000L)]
        │
        ├─ handleToolIfNeeded(response)                     [suspending IO]
        │
        ├─ memoryManager.recordChatMessage()                [suspending IO]
        │
        └─ if (queryType == ACTION && !wasToolCall):
              viewModelScope.launch(Dispatchers.IO) {       [nested coroutine: IO pool]
                  cognitiveLoop.process(BrainInput, fullResponse)   ← CURRENT: legacy path
              }
    }
```

**The nested `viewModelScope.launch(Dispatchers.IO)` is fire-and-forget.** The parent coroutine does not await it. This means UCL execution runs in parallel with `refreshSessions()`, paywall checks, and UI updates. This design must be preserved in the migration — `executeGraph()` must also be fire-and-forget from the outer coroutine.

---

## 2. UCL.executeGraph() Design Analysis

### 2.1 Is it synchronous or streaming?

**It is suspending but NOT streaming.** The function signature is:
```kotlin
suspend fun executeGraph(graph: TypedPlanGraph, workspace: SandboxWorkspace): GraphExecutionResult
```

It suspends the caller until the full DAG completes. It does NOT emit a Flow. Progress updates go through `ExecutionStatusBus` (a global `MutableStateFlow`) rather than streaming tokens back.

**Implication:** The caller launches it fire-and-forget (same pattern as current `cognitiveLoop.process`) and `ChatViewModel` observes `ExecutionStatusBus.status` as a `StateFlow` to render progress. No streaming integration needed.

### 2.2 Can it coexist with current streaming generation?

**YES, safely.** The two paths operate on different resources:
- LLM token streaming uses `LlamaManager.llamaDispatcher` (single-threaded, serialized by `lifecycleLock`)
- `executeGraph()` nodes run on `Dispatchers.IO` via `supervisorScope { async { } }` — the multi-threaded pool

`executeGraph()` is launched *after* the full LLM response is available (post-streaming). It does not call `LlamaManager` directly. The sub-agents it dispatches to (`AndroidAgent`, `CloudBrowserAgent`) do not use `LlamaManager`. CodingAgent/ResearchAgent emit `AgentEvent.Delegate("llm_backend")` — this is returned as a result string, not an actual LLM call from within the graph. **No mutex contention.**

### 2.3 Can it deadlock with LlamaManager mutex?

**NO deadlock possible** under the current architecture for these reasons:

1. `executeGraph()` is launched only *after* `LlamaManager.generateStream()` completes
2. No sub-agent inside `runNode()` calls `LlamaManager` directly
3. `supervisorScope { async { } }` runs nodes on `Dispatchers.IO`, not `llamaDispatcher`
4. `lifecycleLock` (coroutine `Mutex`) is only held during model load/unload in `LlamaManager`, not during token generation
5. `TypedPlanGraph` uses JVM `@Synchronized` (reentrant monitor on the graph object) — independent of `llamaDispatcher`

**Future deadlock risk (to document):** If a sub-agent were ever to make a *synchronous* `LlamaManager.generateStream()` call (which it currently doesn't), it would deadlock if `executeGraph()` were somehow called mid-stream. The current architecture prevents this — document as a constraint for future sub-agent implementations.

### 2.4 Can it create recursive orchestration loops?

**Yes — moderate risk, currently mitigated by delegation shell pattern.**

The chain is: `executeGraph()` → `runNode()` → `SubAgentRegistry.route()` → `SubAgent.execute()`. If a sub-agent were to call back into `ChatViewModel.sendMessage()` (which would trigger another `executeGraph()`), you'd have infinite recursion.

Current mitigation: no sub-agent has access to `ChatViewModel`. The delegation shells (`CodingAgent`, etc.) emit `AgentEvent.Delegate` and return — they do not call the ViewModel. `AndroidAgent` uses `AccessibilityCommandBridge`. `CloudBrowserAgent` uses `OkHttp`.

**Constraint for migration:** The `SubAgentContext.nestingDepth` field must be set to `1` in `executeGraph()` calls (already done in the existing `runNode()` implementation at line 266: `nestingDepth = 1`). If a future sub-agent recursively creates a `SubAgentContext`, it must check `nestingDepth` before routing.

### 2.5 Graph execution concurrency

`TypedPlanGraph` is safe for concurrent node execution:
- All mutation methods are `@Synchronized` on the graph instance
- `readyNodes()`, `markRunning()`, `markDone()`, `markFailed()`, `resetForRetry()`, `snapshot()` are all `@Synchronized`
- Wave results are processed sequentially after `awaitAll()` returns (by design — prevents Abort/Skip races)

`SandboxWorkspace.snapshot()` and `SandboxWorkspace.log()` are safe: `synchronized(actionLog)` and `synchronized(snapshots)`.

`ExecutionStatusBus` is safe: `MutableStateFlow` is thread-safe.

**No concurrency issues found.**

### 2.6 Does the reflection system still compile after Phase 1?

**YES — both files are clean:**
- `ExecutionReflector.kt` imports: `GraphSnapshot`, `NodeExecutionRecord` — both exist ✓
- `PlanQualityScorer.kt` imports: `NodeStatus`, `TypedPlanGraph` — both exist ✓
- Neither imports any deleted engine ✓

### 2.7 CRITICAL: UCL.adaptationEngine broken reference

**UCL line 97 references `ServiceLocator.plannerAdaptationEngine` which was deleted in Phase 1.**

```kotlin
private val adaptationEngine
    by lazy { runCatching { ServiceLocator.plannerAdaptationEngine }.getOrNull() }
```

The `runCatching { }.getOrNull()` means this will silently return `null` at runtime — `NoSuchFieldException` is caught. The `adaptationEngine?.ingest(...)` and `adaptationEngine?.applyToGenerator(...)` calls are null-safe (`?.`), so they are no-ops.

**Verdict:** Does not crash. Does not affect correctness of graph execution. The adaptation feedback loop (which was a nice-to-have, not core) is silently disabled. **Must be fixed before shipping, but does not block migration.**

### 2.8 WorkspaceRegistry — NOT FOUND

`WorkspaceRegistry.kt` was not found in the source tree. `UCL.executeGraph()` calls:
- `WorkspaceRegistry.get(graph.goalId)` in the default parameter
- `WorkspaceRegistry.release(graph.goalId)` in the finally block

**This is a compile-time error if WorkspaceRegistry doesn't exist.** Must verify before wiring.

---

## 3. Runtime Sequence Diagram (Target State)

```
User input: "send email to John about the meeting"
  │
  ├─ QueryClassifier → ACTION (starts with "send")
  │
  └─ viewModelScope.launch {
        ├─ LLM generates: "I'll send that email for you.\n[{...action plan JSON...}]"
        │   (streaming tokens → _streamingText → ChatScreen)
        │
        ├─ fullResponse captured
        ├─ message saved to Room DB
        ├─ message displayed in chat
        │
        └─ [FEATURE FLAG CHECK: AIRI_EXECUTE_GRAPH_ENABLED]
              │
              ├─ [FLAG OFF — current behavior]
              │   viewModelScope.launch(IO) { cognitiveLoop.process(BrainInput, fullResponse) }
              │   → createActionPlanFromLLM(fullResponse) → CommandRouter.execute(steps)
              │   → fire-and-forget, no UI feedback
              │
              └─ [FLAG ON — new behavior]
                  viewModelScope.launch(IO) {
                      val graph = planGenerator.createDAGPlanFromLLM(fullResponse, input)
                                  .toTypedPlanGraph()         ← new conversion needed
                      ExecutionStatusBus.reset()              ← prevent stale state
                      cognitiveLoop.executeGraph(graph)       ← parallel-wave DAG
                  }
                  │
                  ├─ ExecutionStatusBus.onGraphStarted() → AgentState → ChatScreen overlay
                  ├─ Wave 1: supervisorScope { async { runNode(emailNode) } }.awaitAll()
                  │   └─ SubAgentRegistry.route("send email") → ProductivityAgent
                  │       └─ ProductivityAgent.execute() → AgentEvent.Complete
                  ├─ ExecutionStatusBus.onNodeCompleted() → progress update
                  ├─ PlanQualityScorer.score() → pre-flight check
                  ├─ ExecutionReflector.reflect() → ReflectionReport
                  └─ ExecutionStatusBus.onGraphCompleted() → AgentState.isWorking = false
    }
```

---

## 4. Migration Strategy

### Step 4.1 — Fix pre-migration blockers (must happen first, in this order)

**4.1.1 — Fix UCL.adaptationEngine dead reference**
Remove the `by lazy { ServiceLocator.plannerAdaptationEngine }` pattern. Since `PlannerAdaptationEngine` is deleted, replace with `null` and document that adaptation is deferred to Phase 3.

**4.1.2 — Verify/create WorkspaceRegistry**
Confirm `WorkspaceRegistry.kt` exists. If missing, create a minimal implementation that satisfies `UCL.executeGraph()`. This is a compile blocker.

**4.1.3 — Verify ActionPlan → TypedPlanGraph conversion path**
`PlanGenerator.createDAGPlanFromLLM()` returns `ActionPlan`. `UCL.executeGraph()` takes `TypedPlanGraph`. Verify the conversion path exists or create `ActionPlan.toTypedPlanGraph()` extension.

**4.1.4 — Add feature flag**
Add `AIRI_EXECUTE_GRAPH_ENABLED` to `BuildConfig` (default: false in debug, false in release). No behavior change yet.

### Step 4.2 — Integration (behind flag)

Replace the `if (queryType == ACTION)` block in `ChatViewModel.sendMessage()`:

```kotlin
// BEFORE
viewModelScope.launch(Dispatchers.IO) {
    runCatching { cognitiveLoop.process(BrainInput(text=trimmedInput), fullResponse) }
        .onFailure { Log.w("AIRI_UCL", "UCL.process failed: ${it.message}") }
}

// AFTER (flag-gated)
viewModelScope.launch(Dispatchers.IO) {
    if (BuildConfig.AIRI_EXECUTE_GRAPH_ENABLED) {
        runCatching {
            val plan  = cognitiveLoop.planGenerator.createDAGPlanFromLLM(fullResponse, trimmedInput)
            val graph = plan.toTypedPlanGraph(goalId = sessionId)
            cognitiveLoop.executeGraph(graph)
        }.onFailure { e ->
            Log.w("AIRI_UCL_GRAPH", "executeGraph failed: ${e.message}")
            // Fallback to legacy path on failure — no user-visible change
            runCatching { cognitiveLoop.process(BrainInput(text=trimmedInput), fullResponse) }
        }
    } else {
        runCatching { cognitiveLoop.process(BrainInput(text=trimmedInput), fullResponse) }
            .onFailure { Log.w("AIRI_UCL", "UCL.process failed: ${it.message}") }
    }
}
```

### Step 4.3 — Observability wiring

Verify `ChatViewModel` already collects `ExecutionStatusBus.status`. If not, add:
```kotlin
init {
    viewModelScope.launch {
        ExecutionStatusBus.status.collect { agentState ->
            _agentState.value = agentState
        }
    }
}
```
`ChatScreen` already displays `agentState` (confirmed: `val agentState by viewModel.agentState.collectAsState()`).

---

## 5. Rollback Strategy

The feature flag means rollback = `AIRI_EXECUTE_GRAPH_ENABLED = false`. No code change required. The legacy `cognitiveLoop.process()` path is the fallback inside the new flag block, so even a partial graph failure falls back gracefully. The flag can be server-controlled via Remote Config for a hot-rollback without a release.

---

## 6. Staged Rollout Plan

| Stage | Flag state | Who sees it | Success criteria |
|---|---|---|---|
| **0 — Pre-merge** | false (default) | CI only | Compiles, all audit checks pass |
| **1 — Debug** | true in debug builds | Developers | executeGraph fires for ACTION queries, ExecutionStatusBus updates observed in debug overlay |
| **2 — Internal** | true via Remote Config flag for internal users | 5% internal | No crashes, no ANRs, ExecutionReflector produces valid ReflectionReport |
| **3 — Limited release** | 10% by Remote Config | 10% of users | P99 latency < 10s overhead, crash-free rate maintained |
| **4 — Full release** | true default | All users | Remove flag, delete legacy path |

---

## 7. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `WorkspaceRegistry` missing → compile failure | BLOCKER | Verify/create before Step 4.1 |
| `adaptationEngine` returns null silently | LOW | Document, fix before Stage 3 |
| `createDAGPlanFromLLM` returns malformed graph | MEDIUM | PlanQualityScorer rejects below 0.35 confidence; fallback to legacy |
| All sub-agents are delegation shells → graph completes instantly with `[delegated to LLM]` | EXPECTED | Correct behavior for current sub-agent implementations; real output comes later when sub-agents are made real |
| Long-running graph blocks ViewModel cleanup | LOW | `viewModelScope` cancellation propagates to `supervisorScope`; `WorkspaceRegistry.release()` is in `finally` |
| Nested launch creates orphan coroutine | LOW | `viewModelScope.launch` is lifecycle-bound; ViewModel.onCleared cancels it |
| TokenCounting double-counts if graph re-calls LLM | N/A | No sub-agent calls LlamaManager currently |
| Memory growth from WorkspaceRegistry accumulating snapshots | LOW | `WorkspaceRegistry.release()` in finally block; `SandboxWorkspace.snapshots` is bounded |

---

## 8. Pre-Migration Checklist (must all pass before implementing Step 4.2)

- [ ] `WorkspaceRegistry.kt` exists and is compilable
- [ ] `ActionPlan.toTypedPlanGraph()` or equivalent conversion exists
- [ ] `UCL.adaptationEngine` dead reference fixed (returns null cleanly, not via exception)
- [ ] `ExecutionReflector` imports verified clean (confirmed ✓)
- [ ] `PlanQualityScorer` imports verified clean (confirmed ✓)
- [ ] `SandboxWorkspace.snapshot()` thread-safety verified (confirmed ✓ via `synchronized(snapshots)`)
- [ ] `ExecutionStatusBus.status` collection confirmed in `ChatViewModel.init`
- [ ] Feature flag `AIRI_EXECUTE_GRAPH_ENABLED` added to `gradle.properties` / `BuildConfig`
- [ ] Audit suite still passes 26/0 after all pre-migration fixes
