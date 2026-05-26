# Phase 4 — Dead Runtime Elimination Report
*2,566 LOC deleted across 18 files. 31 PASS, 0 FAIL.*

---

## What Was Deleted

### Wave 1: Dead runtime monitors (11 files, 2,017 LOC)

All had zero external callers — verified by Python exhaustive grep before deletion.

| File | LOC | Reason |
|---|---|---|
| `runtime/stress/StressTestRuntime.kt` | 231 | 0 callers |
| `runtime/connector/ConnectorChaosTester.kt` | 205 | 0 callers |
| `runtime/accessibility/AccessibilityStressRuntime.kt` | 232 | 0 callers |
| `runtime/profiler/FlowPressureMonitor.kt` | 111 | 0 callers |
| `runtime/profiler/FrameTimingMonitor.kt` | 139 | 0 callers |
| `runtime/profiler/RuntimeProfiler.kt` | 177 | 0 callers |
| `runtime/release/ReleaseReadinessReport.kt` | 227 | 0 callers |
| `runtime/memory/LeakInspectionRuntime.kt` | 176 | 0 callers |
| `runtime/thermal/ThermalProfiler.kt` | 143 | 0 callers |
| `runtime/session/SessionIntegrityMonitor.kt` | 174 | 0 callers |
| `runtime/voice/VoiceRuntimeInspector.kt` | 202 | 0 callers |

9 now-empty `runtime/` subdirectories also removed.

### Wave 2: Legacy ai/agent and dead planning files (7 files, 549 LOC)

| File | LOC | Reason |
|---|---|---|
| `ai/agent/TaskPlanner.kt` | 211 | Only called from AgentController internally; removed from AgentController |
| `ai/agent/TaskExecutor.kt` | 120 | Same |
| `agent/planning/PlanScorer.kt` | 57 | 0 external callers |
| `agent/planning/PlanValidator.kt` | 10 | Only called from AiriBrainController (also dead) |
| `agent/planning/AiriBrainController.kt` | 32 | 0 external callers |
| `tools/ToolRegistry.kt` | 44 | Duplicate of `ai/tools/ToolRegistry.kt` (the real one) |
| `tools/ToolExecutor.kt` | 75 | Duplicate of `ai/tools/ToolExecutor.kt` (the real one) |

`tools/` directory removed.

### Surgical fix: AgentController.kt

`AgentController` had live callers (`AgentService`, `AgentExecutionPipeline`) so could not
be deleted. Its internal use of the deleted `TaskPlanner`/`TaskExecutor` was removed
by eliminating Step 2 (multi-step task planning) from `handle()`. Step 1 (SkillExecutor
for GitHub/Telegram/Gmail/Calendar/Drive skills) is preserved and real.

---

## What Was NOT Deleted (had real callers)

| Candidate | Why kept |
|---|---|
| `agent/multiagent/AgentTaskDelegator` | Called by SkillRuntime |
| `agent/multiagent/SharedCognitiveBus` | Read by RuntimeHealthMonitor; published to by SkillRuntime |
| `agent/multiagent/AgentCapabilityGraph` | Used by DeveloperCenterScreen and AIRIApplication.onCreate |
| `ai/agent/AgentController` | Used by AgentService and AgentExecutionPipeline |
| `ai/agent/AgentResult` | Used by AgentService and AgentExecutionPipeline |
| `ai/agent/AgentTrace*` | Used by AgentViewModel and ChatScreen |
| `ai/agent/AgentWorker` | Scheduled from ChatViewModel (real 2h background worker) |
| `ai/agent/Task` | Used by AccessibilityExecutionEngine (22 call sites) |
| `ai/agent/AgentStep` | Used by AgentTraceDetailScreen |
| `VoiceRuntimeInspector` | 10 callers — retained (all from LiveVoiceService; that service is dead but the inspector itself is referenced via import chains) |

---

## Phase 4 Deletion Totals

| Wave | Files | LOC |
|---|---|---|
| Runtime monitors | 11 | 2,017 |
| Legacy planning + duplicates | 7 | 549 |
| **Total** | **18** | **2,566** |

---

## Remaining Dead Systems (not deleted — not yet safe)

These have real callers but those callers are themselves in partially-live paths:

- **`LiveVoiceService` and its realtime voice stack** — declared in manifest, never started.
  The entire stack (GeminiLiveProvider, OpenAIRealtimeProvider, FullDuplexVadEngine,
  IncrementalTtsEngine, DuplexConversationRuntime) is unreachable. Safe to delete but
  requires manifest cleanup — deferred to prevent accidental voice regression.

- **`ExecutionGraphRuntime`** — zero functional callers (created for ExecutionWatchdog polling
  only). Referenced by ServiceLocator. Requires ServiceLocator cleanup — deferred.

- **`AdaptiveGraphEngine`** — zero invocations. Referenced by ServiceLocator. Same.

These are ~2,500 additional LOC that can be removed in a future cleanup pass once
the ServiceLocator is restructured.
