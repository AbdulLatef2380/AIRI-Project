# Phase 0 — Duplicate Systems Report

## Two Parallel Agent Stacks

| Concern | Legacy stack (`ai/agent/*`) | Modern stack (`agent/*`) |
|---|---|---|
| Controller | `AgentController.kt` | `ProductionAgentOrchestrator.kt` |
| Planner | `TaskPlanner.kt` | `agent/planning/PlanGenerator.kt`, `TypedPlanGraph.kt` |
| Executor | `TaskExecutor.kt` | `agent/execution/runtime/ExecutionGraphRuntime.kt`, `AgentExecutor.kt` |
| Step DTO | `TaskStep.kt` | `agent/execution/task/TaskStep.kt` |
| Result DTO | `AgentResult.kt` | `agent/execution/runtime/*` results |
| Trace | `ai/agent/trace/*` | `agent/observability/*` |

The legacy stack is referenced by `ChatViewModel` only via a single `agentController` field. **Phase 2 action: migrate that single use site to `ProductionAgentOrchestrator.executeSingle` and delete the legacy stack.**

## Decision/Adaptation/Reflection Engine Inflation

```
agent/decision/ ── 12 files
  AdaptiveBehaviorEngine, AdaptiveDecisionEngine, BehaviorPolicy,
  ConfidenceScorer, DecisionEngine, DialogueRhythmEngine, EmotionEngine,
  GuardianEngine, PatternAggregator, RelationshipBoundaryPolicy,
  RiskProvider, SuggestionScoreEngine
agent/adaptation/ ── 4 files
  PlannerAdaptationEngine, PlanAdaptationHints,
  StrategyEvolutionEngine, FailureIntelligenceEngine
agent/reflection/ ── 3 files
agent/learning/   ── 7 files (incl. reinforcement/)
```

Target shape (Phase 2):

```
agent/policy/PolicyGate.kt          ← merged from GuardianEngine
                                       + RelationshipBoundaryPolicy
                                       + BehaviorPolicy
agent/reflection/Reflector.kt       ← single reflector
agent/scoring/OutcomeScorer.kt      ← merged ConfidenceScorer + PlanQualityScorer
```

26 files → 3.

## Memory System Duplication

- `memory/` (Room-backed, primary)
- `ai/prompt/MemoryStore.kt` (in-context shadow)

Phase 4 will introduce a **single `MemoryFacade`** with explicit episodic / semantic / procedural / working tiers, all backed by `memory/` Room schema.

## Voice Pipeline Duplication

Three overlapping ways to do voice currently coexist:

1. Vosk-only local: `VoskEngine`, `HotwordService`, `IncrementalTtsEngine`.
2. Full-duplex local: `FullDuplexVadEngine`, `VoiceInterruptController`, `DuplexConversationRuntime`.
3. Cloud-realtime: `voice/realtime/{OpenAIRealtimeProvider,GeminiLiveProvider,RealtimeVoiceProvider}`.

Phase 6 will pick **one** and delete the others. Default recommendation: keep local full-duplex (privacy-first messaging matches), make cloud-realtime an optional connector rather than an in-tree sibling.

## Diagnostics Screen Duplication

Six overlapping diagnostics screens:

`ExecDiagnosticsScreen`, `RuntimeDiagnosticsPanel`, `DebugPanelScreen`, `DebugScreen`, `ModelPerformanceScreen`, `PerformanceScreen`.

Phase 7 will collapse them into a single **DeveloperCenterScreen** (already exists at `screen_developer_center`) with tabbed sections.

## Routing Duplication

5 files matching `*Router*.kt`:

- `agent/execution/command/CommandRouter.kt` — primary command dispatcher
- `connector/AgentRouter.kt` — connector-side router (potentially redundant with above)
- `execution/cloud/OpenRouterAdapter.kt` — provider adapter, OK
- `execution/router/RuntimeRouter.kt` — local/remote routing
- `voice/VoiceAgentRouter.kt` — voice-specific (OK)

Phase 2 will keep `CommandRouter` + `RuntimeRouter` and absorb `connector/AgentRouter` into `RuntimeRouter` if redundant.
