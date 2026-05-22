# Phase 0 — Dead Systems Report

A "dead system" is code that is instantiated by `ServiceLocator` / `AIRIApplication` but **never reached from the chat path** (`ChatViewModel` → backend → response).

## Confirmed Dead (no production call sites from chat path)

| Component | Hit count (incl. ServiceLocator wiring) | Notes |
|---|---|---|
| `ReActPlanner` | 5 | Only called by transitively-dead `executeGraph`/`CoTEngine`. |
| `CoTEngine` | 3 | Called only by `ReActPlanner`. |
| `PlannerAdaptationEngine` | 10 | Called by `executeGraph` (unreachable) and `StrategyEvolutionEngine` (also unreached). |
| `AdaptiveIntelligenceEngine` | 3 | ServiceLocator + 1 internal ref. |
| `ModelGovernanceEngine` | 3 | ServiceLocator + internal. |
| `DurableTaskManager` | 3 | No UI activator wires it to a goal. |
| `StrategyEvolutionEngine` | 5 | Reads from dead engines. |
| `FailureIntelligenceEngine` | 5 | Reads from dead engines. |
| `EthicalMemoryController` | 2 | ServiceLocator only. |
| `AgentCapabilityGraph` | (registered at `Application.onCreate`) | Never queried by chat path. |
| `UnifiedCognitiveLoop.executeGraph` | 5 hits in runtime files | The 340-LOC parallel-wave engine; **chat path uses `process(input, llmResponse)` instead**, bypassing the DAG runtime. |

## Sub-Agents That Are LLM-Delegation Shells

Located under `app/src/main/java/com/airi/assistant/agent/subagent/impl/`:

| Sub-agent | Status |
|---|---|
| `CodingAgent.kt` | Emits `AgentEvent.Delegate("llm_backend", …)` then `Complete("[delegated to LLM — streaming response]")`. |
| `ResearchAgent.kt` | Same delegation pattern. |
| `MediaGenerationAgent.kt` | Same. |
| `DocumentProcessorAgent.kt` | Same. |
| `MemoryAgent.kt` | Same. |
| `ProductivityAgent.kt` | Same. |
| `CloudBrowserAgent.kt` | **Real** — performs OkHttp fetch + DOM extraction. |
| `AndroidAgent.kt` | **Real** — uses `AccessibilityExecutionEngine`. |
| `LocalBrowserOperator.kt` | Partial. |

7 of 9 are not agents. They are prompt-template builders that defer back to the same LLM.

## Unreachable UI Screens (not linked from any visible nav path)

- `OBSERVABILITY` (`ObservabilityScreen`)
- `EXEC_DIAGNOSTICS` (`ExecDiagnosticsScreen`)
- `DEBUG_PANEL`, `DEBUG_SCREEN` (two overlapping debug dashboards)
- `SANDBOX_WORKSPACE` (`SandboxWorkspaceScreen` — fronts `SandboxExecutor`, which only allows `ls/cat/git`/etc. on Android)
- `WORKSPACE`
- `TERMINAL` (`TerminalScreen` — runtime cannot run code)
- `MODEL_PERFORMANCE`, `PERFORMANCE` (two overlapping perf dashboards)
- `AGENT_TRACE_DETAIL`, `AGENT_LOGS`
- `REFERRALS`

## Redundant / Theatrical Subsystems

- `SandboxExecutor` Argv allowlist of `ls cat echo mkdir cp mv rm find grep sed awk head tail wc sort uniq git zip unzip tar` — no `python`, no `node`, no `clang`. Cannot run real agent tasks on stock Android.
- `AccessibilityExecutionEngine.planActions` — heuristic planner described as "natural language → plan" in README; no real LLM-driven OBSERVE/PLAN/EXECUTE/VERIFY/RECOVER cycle confirmed.
- All `agent/decision/*` engines except `ConfidenceScorer` (used by `PlanQualityScorer`) — overlapping responsibilities, multiple of them touched once at startup and never again.

## Action Plan (executed in subsequent phases)

- **Phase 2** will delete the legacy `ai/agent/*` stack and collapse `agent/decision/*` into a single `PolicyGate`.
- **Phase 1** will remove unreachable UI screens and dead nav entries.
- **Phase 3** will either wire `executeGraph` into the chat path or delete it. ReAct/CoT will be reinstated only if the new tool-use loop demands them.
