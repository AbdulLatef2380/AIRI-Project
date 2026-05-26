# Phase 0 — Risks

## CRITICAL
1. **UCL.executeGraph dead path** — 340 LOC of correct DAG runtime is never reached. Chat path uses a pattern-matching command executor instead. Every "intelligent planning" claim in the README is false.
2. **Sub-agents are delegation shells** — 7 of 9 sub-agents emit `AgentEvent.Delegate("llm_backend")` and complete with `"[delegated to LLM]"`. No specialized capability, no tool use, no real agent behavior.
3. **No tool-use loop** — LLM produces text, not tool calls. There is no structured tool-call grammar, no observation injection, no replan on failure.
4. **ChatViewModel 3,277 LOC** — any significant bug in chat has 3,277 lines of search space. Untestable.
5. **Prompt-injection via accessibility** — `AndroidAgent` drives `AccessibilityExecutionEngine`. `CloudBrowserAgent` fetches untrusted web content. The chain `web fetch → LLM processing → accessibility tap` is a confused-deputy attack with no taint tracking.

## HIGH
6. 12 decision engines in `agent/decision/` with 0 callers in chat path.
7. Dual agent stacks — `ai/agent/*` and `agent/*` both compile, increasing maintenance surface.
8. ServiceLocator with 104 singletons — initialization order bugs will be silent.
9. `SandboxExecutor.KOTLIN_SCRIPT` / `PYTHON_SCRIPT` return UnsupportedOnDevice — UI implies it works.
10. `QUERY_ALL_PACKAGES` permission + Play Store policy risk.

## MEDIUM
11. Voice: 3 overlapping pipelines (Vosk local, full-duplex, cloud realtime) — pick one.
12. llama.cpp vendored in-tree (275 C/C++ files) — 20+ min builds, manual upstream sync.
13. 8 screens with no reachable nav path.
14. Privacy marketing contradicts Firebase/cloud integrations.

---

# Phase 1 — Next Steps

## Immediate surgical actions (Phase 1 — no regressions possible)

### 1.1 Delete the 12 dead decision engines
**Files to delete:**
- `agent/decision/AdaptiveBehaviorEngine.kt`
- `agent/decision/AdaptiveDecisionEngine.kt`
- `agent/decision/BehaviorPolicy.kt`
- `agent/decision/DecisionEngine.kt`
- `agent/decision/DialogueRhythmEngine.kt`
- `agent/decision/EmotionEngine.kt`
- `agent/decision/GuardianEngine.kt`
- `agent/decision/PatternAggregator.kt` (1 execution caller — verify)
- `agent/decision/RelationshipBoundaryPolicy.kt`
- `agent/decision/RiskProvider.kt`
- `agent/decision/SuggestionScoreEngine.kt`

Keep: `agent/decision/ConfidenceScorer.kt` (called by PlanQualityScorer).

### 1.2 Delete dead learning/adaptation engines
- `agent/learning/StrategyEvolutionEngine.kt`
- `agent/learning/FailureIntelligenceEngine.kt`
- `agent/learning/PersistentLearningStore.kt`
- `agent/adaptation/AdaptiveIntelligenceEngine.kt`
- `agent/adaptation/PlannerAdaptationEngine.kt` (only called from dead executeGraph)

### 1.3 Delete dead reflection engines (their callers are also dead)
- NOT YET — keep `PlanQualityScorer` and `ExecutionReflector`. Phase 2 will wire these into the real path.

### 1.4 Delete fake UI sandbox capability
- In `SandboxWorkspaceScreen`, replace the "Run Code" button with an honest "Code execution not yet available on Android" empty state.

### 1.5 Remove ServiceLocator references to deleted engines
After each deletion, remove the corresponding `by lazy` in `ServiceLocator.kt`.

### 1.6 Update audit script
Add checks for all deleted files.

## Phase 2 Preview (after Phase 1 is committed)
- Unify voice pipeline
- Wire `UCL.executeGraph` into chat path for ACTION queries
- Collapse sub-agents to ToolRegistry entries
- Begin ChatViewModel split
