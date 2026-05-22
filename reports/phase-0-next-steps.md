# Phase 0 — Next Steps

Each step below maps to one or more **commit + push** events.

## Phase 1 — Stop the bleeding (deletion-only, no behavior change)

1. Remove unreachable UI screens from the nav graph; delete the screen files only after confirming no other entry points reference them. Targets: `WelcomeScreen` (replaced by Login flow), `DebugScreen` vs `DebugPanelScreen` (keep one), `PerformanceScreen` vs `ModelPerformanceScreen`, `SandboxWorkspaceScreen`, `TerminalScreen`, `WorkspaceScreen`. Per pass, push.
2. Remove `QUERY_ALL_PACKAGES` from `AndroidManifest.xml` if not actually queried.
3. Tighten remote-LLM connectors: gate behind a runtime privacy-mode flag.
4. Remove placeholder buttons in chat overlays that don't have a working backend (paywall triggers on dead routes, etc.).

## Phase 2 — Architecture consolidation

1. Migrate `ChatViewModel.agentController` callers to `ProductionAgentOrchestrator.executeSingle`. Push.
2. Delete `ai/agent/{AgentController, TaskPlanner, TaskExecutor, Task, TaskStep, AgentResult, background, trace}`. Push.
3. Collapse `agent/decision/*` into `agent/policy/PolicyGate.kt`. Keep `ConfidenceScorer` (used by `PlanQualityScorer`). Delete the rest. Push.
4. Delete `ReActPlanner`, `CoTEngine`, `PlannerAdaptationEngine`, `AdaptiveIntelligenceEngine`, `ModelGovernanceEngine` (project's own dead-systems report already confirms). Push.
5. Delete `agent/multiagent/AgentCapabilityGraph` and its registration in `AIRIApplication`. Push.

## Phase 3 — Real cognitive loop

1. Wire `processCognitiveInput` → `executeGraph` so every chat turn flows through the DAG runtime, OR delete the DAG runtime. Pick wire.
2. Introduce a tool-call grammar (GBNF on the native side) so the LLM can emit structured tool calls.
3. Implement the `LLM → tool_call → observation → LLM` loop with streaming, retry, and replan.
4. Convert sub-agents to `ToolRegistry` entries (each: name, JSON schema, executor).

## Phase 4 — Persistent memory

Single `MemoryFacade` with episodic/semantic/procedural/working tiers, backed by `memory/` Room schema. Promote+decay rules. Embedding-backed retrieval already exists.

## Phase 5 — Real agent runtime

`agent_runs` + `agent_steps` + `agent_artifacts` Room schema. "AIRI was working on X — resume?" UX. Pause/resume/cancel. Trust-boundary tags on every artifact.

## Phase 6 — Performance & stability

JNI split (`libairi_embed.so`, `libairi_vision.so`). Voice pipeline pick-one. ANR audit. Battery/thermal handling.

## Phase 7 — Productization

UI screens 38 → 8 + dev hub. Honest privacy copy. CI eval against golden set. `FetchContent` llama.cpp. Final release validation.

---

Push order is enforced: commit + push after every step that crosses a subsystem boundary, never accumulate unverified work.
