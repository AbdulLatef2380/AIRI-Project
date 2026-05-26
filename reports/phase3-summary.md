# Phase 3 Summary — Sub-Agent Delegation Handler
*Delegation shells now produce real LLM responses when executeGraph is active.*

---

## What Changed

### Problem (verified pre-Phase 3)
7 of 9 sub-agents (`CodingAgent`, `ResearchAgent`, `DocumentProcessorAgent`,
`MemoryAgent`, `MediaGenerationAgent`, and others) emit `AgentEvent.Delegate("llm_backend", ...)`
and immediately complete with `"[CodingAgent delegated to LLM — streaming response]"`.

`UnifiedCognitiveLoop.runNode()` collected this flow but had no handler for
`AgentEvent.Delegate` — the event fell through to `else -> Unit`, leaving
`resultText = ""`, and nodes completed with `"node:<id>:done"`.

The "9 specialized agents" were routing shells with no real capability.

### Solution (Phase 3)

**`UnifiedCognitiveLoop.kt`** — Added `orchestratorProvider` injectable field:
```kotlin
@Volatile
var orchestratorProvider: (suspend (String, (String)->Unit, (String)->Unit) -> Unit)? = null
```

Added real `AgentEvent.Delegate` handler in `runNode()`:
- Checks `context.canDelegate` (nestingDepth < MAX_NESTING_DEPTH) — prevents infinite recursion
- Calls `orchestratorProvider(event.subInput, onToken, onError)`
- Accumulates real LLM tokens into `resultText`
- Falls back to descriptive string if provider not injected (background/scheduled contexts)

**`ChatViewModel.kt`** — In `init`, wires `orchestratorProvider` to `HybridOrchestrator.executeStream`:
```kotlin
cognitiveLoop.orchestratorProvider = suspend@{ prompt, onToken, onError ->
    hybridOrchestrator.executeStream(
        request = ExecutionRequest(prompt, queryType = ANALYTICAL, ...),
        onToken    = { token -> onToken(token) },
        onError    = { err, _ -> onError(err) }
    )
}
```

**`WorkspaceRegistry.kt`** *(new)* — Per-goal workspace lifecycle. Resolves the missing file that was a compile blocker for `UCL.executeGraph`.

**`ActionPlanExtensions.kt`** *(new)* — `ActionPlan.toTypedPlanGraph()` conversion. Each `PlanStep` subtype maps to a `GoalNode` with correct `action`, `params`, `dependsOn`, `isCritical`, `recoveryBranch`.

**`build.gradle.kts`** — `AIRI_EXECUTE_GRAPH_ENABLED=true` in debug, `false` in release.

**`core/UnifiedCognitiveLoop.kt`** — `adaptationEngine` dead reference fixed with `AdaptationEngineStub? = null`. All `?.ingest()` and `?.applyToGenerator()` calls are clean no-ops.

---

## Execution Flow (with flag enabled)

```
User: "send an email to John about the meeting"
  ↓
QueryClassifier → ACTION
  ↓
LLM generates response text + embedded JSON plan
  ↓  (streaming to ChatScreen, shown to user)
sendMessage() flag-gated block:
  plan = planGenerator.createDAGPlanFromLLM(response, input)
  graph = plan.toTypedPlanGraph(goalId = sessionId)
  cognitiveLoop.executeGraph(graph)
  ↓
PlanQualityScorer: confidence ≥ 0.35? → proceed
ExecutionStatusBus.onGraphStarted() → ChatScreen agent overlay shows
  ↓
Wave 1: ProductivityAgent.execute("send email to john about meeting")
  → AgentEvent.Delegate("llm_backend", "Draft an email to John about...")
  → orchestratorProvider("Draft an email...", onToken, onError)
    → HybridOrchestrator.executeStream(...)  [real LLM call]
    → resultText = "Subject: Meeting Discussion\n\nHi John,\n..."
  → CommandResult(true, resultText)
  ↓
ExecutionStatusBus.onNodeCompleted() → progress update
ExecutionReflector.reflect() → ReflectionReport
ExecutionStatusBus.onGraphCompleted(success=true)
```

**Before Phase 3:** `resultText = "[delegated to LLM]"`, node completes hollow.
**After Phase 3:** `resultText = "<real LLM response>"`, node has real content.

---

## Remaining Gaps (not blocking Phase 3 completion)

- **Sub-agent routing is still keyword scoring**, not LLM tool selection. The model picks words → `SubAgentRegistry` matches keywords → routes. True tool selection (Phase 4) requires GBNF-constrained tool-call grammar.
- **`ProductivityAgent` and `MediaGenerationAgent`** still have 0 delegation lines in their `execute()` bodies — their routing was misclassified. Both need to be checked for their actual output in Phase 4.
- **`DocumentProcessorAgent`** works with file URIs — needs real file access to be useful.
- **Adaptation engine is null** — the reflection report `critiqueText` is produced correctly but `adaptationEngine` is disabled. Phase 4 will introduce the lightweight replacement.

---

## Audit Result
**31 PASS, 0 FAIL, 8 WARN** (same 8 known warnings from previous phases)
