# Phase 2 Pre-Migration Summary
*UCL.executeGraph() integration — pre-migration audit complete, all blockers fixed.*

---

## Audit Findings (verified against actual source)

### Confirmed safe for integration
- `executeGraph()` is suspending, NOT streaming — uses `ExecutionStatusBus` for UI updates
- Zero mutex contention with `LlamaManager` — launched after generation completes, no JNI access
- No deadlock possible — nodes run on `Dispatchers.IO`, `llamaDispatcher` is not touched
- No recursive orchestration loops — no sub-agent has ViewModel access
- `TypedPlanGraph` is `@Synchronized` throughout — safe for parallel wave execution
- `SandboxWorkspace.snapshot()` uses `synchronized(snapshots)` — thread-safe
- `ExecutionStatusBus` is `MutableStateFlow` — thread-safe
- `ExecutionReflector` and `PlanQualityScorer` — no broken imports after Phase 1

### Critical blockers found and fixed

| Blocker | Status |
|---|---|
| `WorkspaceRegistry.kt` missing (compile error) | **FIXED** — created with get/release/pruneStale |
| `ActionPlan.toTypedPlanGraph()` missing | **FIXED** — created `ActionPlanExtensions.kt` |
| `UCL.adaptationEngine` → deleted `PlannerAdaptationEngine` | **FIXED** — replaced with `AdaptationEngineStub? = null` |

---

## Files Created/Modified

**`agent/workspace/WorkspaceRegistry.kt`** *(new)* — Per-goal SandboxWorkspace lifecycle. `get(goalId)` creates lazily; `release(goalId)` in `executeGraph` finally block; `pruneStale()` called on `onTrimMemory`.

**`agent/planning/ActionPlanExtensions.kt`** *(new)* — `ActionPlan.toTypedPlanGraph(goalId)` extension. Converts each `PlanStep` subtype to a `GoalNode` with correct `action`, `params`, `dependsOn`, `isCritical`, and `recoveryBranch` fields.

**`core/UnifiedCognitiveLoop.kt`** — `adaptationEngine` changed from dead lazy ServiceLocator reference to `AdaptationEngineStub? = null`. All `?.ingest` and `?.applyToGenerator` calls are now clean no-ops.

**`app/build.gradle.kts`** — Added `buildConfigField("boolean", "AIRI_EXECUTE_GRAPH_ENABLED", "false")`.

**`ui/viewmodel/ChatViewModel.kt`** — ACTION block now flag-gated:
  - `AIRI_EXECUTE_GRAPH_ENABLED=false` → legacy `cognitiveLoop.process()` (unchanged behavior)
  - `AIRI_EXECUTE_GRAPH_ENABLED=true` → `plan.toTypedPlanGraph()` → `cognitiveLoop.executeGraph(graph)` with automatic fallback to legacy on any exception

**`app/AIRIApplication.kt`** — `onTrimMemory` now calls `WorkspaceRegistry.pruneStale()` in addition to the existing `LowMemoryPressure` EventBus emission.

---

## Current State

Flag is `false` by default. No behavior change in production. The graph execution path is wired but dormant.

To enable for a debug build:
```
# gradle.properties
airiExecuteGraphEnabled=true
```
Or override in `build.gradle.kts` `debug` buildType:
```kotlin
buildConfigField("boolean", "AIRI_EXECUTE_GRAPH_ENABLED", "true")
```

---

## Staged Rollout Next Steps

1. **Stage 0** (current): Flag off everywhere — CI verifies compilation
2. **Stage 1**: Enable in debug buildType — developer testing of `ExecutionStatusBus` overlay
3. **Stage 2**: Remote Config flag for 5% internal users — confirm no crashes, P99 overhead < 2s
4. **Stage 3**: 10% production rollout — monitor `AIRI_GRAPH` log tags
5. **Stage 4**: Full release — remove flag, delete legacy `cognitiveLoop.process()` path from ACTION block

---

## Remaining Risks (from migration plan)

- `adaptationEngine` is permanently null until Phase 3 — feedback loop disabled
- All delegation-shell sub-agents produce `[delegated to LLM]` node outputs — semantically correct but not useful until sub-agents are converted to real tools (Phase 3)
- `createDAGPlanFromLLM` relies on LLM producing valid JSON plan structure — returns no-op single node if JSON absent (handled by PlanQualityScorer, which will reject low-confidence plans)
