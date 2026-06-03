# AIRI — Forensic Verification Report
*Generated from repository state. No modifications. All claims code-verified.*

---

## 1. Repository Inventory

- **406 Kotlin files**
- **68,872 total LOC**

---

## 2. Active Runtime Map

```
User types message
  │
  ▼
ChatViewModel.sendMessage(trimmedInput)       [2,899 LOC god class]
  │
  ├─ QueryClassifier.classifyQuery()
  │     Returns: SIMPLE | ANALYTICAL | ACTION | CREATIVE | UNKNOWN
  │     ACTION is triggered by imperative verb prefix (keyword list):
  │     "send ", "open ", "run ", "create ", "make ", etc.
  │     NOT LLM-based. Keyword match.
  │
  ├─ PolicyEngine.checkSubscription / checkRateLimit   [real]
  │
  ├─ ResponseOptimizer.tryFastResponse()               [real — table lookup]
  │
  ├─ SubAgentRegistry.route(input, context)            [keyword-scored]
  │     ↓ if agent matched AND accessibility permission granted:
  │     ProductionAgentOrchestrator.executeSingle()
  │         └─ SubAgent.execute() [AndroidAgent|ResearchAgent|ProductivityAgent|
  │                                 MemoryAgent|CloudBrowserAgent]
  │     AndroidAgent.execute() → AccessibilityExecutionEngine.executeTask()
  │
  ├─ AgentService.handle()                             [legacy pipeline]
  │
  ├─ PromptCompressor.compose() [with AccessibilityPolicyGuard RAG isolation]
  │
  └─ HybridOrchestrator.executeStream(request)         ← PRIMARY INFERENCE
          │
          ├─ PrivacyGuard.evaluate()
          ├─ RuntimeRouter.route()
          │     ├─ LocalLlamaBackend → LlamaManager.generateStream() → JNI
          │     └─ CloudBackend → RemoteModelExecutor → OpenAI/Gemini/Anthropic
          │
          └─ onComplete(fullResponse):
                  ├─ handleToolIfNeeded()
                  │     → SkillService [old keyword-parsed tool path]
                  │     → if tool matched: llamaManager.generate() [BYPASS]
                  │
                  └─ if QueryType.ACTION && !wasToolCall:
                          agentLoop.run()              ← NEW ITERATIVE LOOP
```

**Separate inference paths NOT through HybridOrchestrator:**

| Path | Trigger | Privacy gate |
|---|---|---|
| `llamaManager.generateWithImage()` | `sendMessageWithImage()` with non-null bitmap/uri | None — LOCAL only, explicitly logged |
| `llamaManager.generate()` | `handleToolIfNeeded()` follow-up synthesis | None |
| `llamaManager.generate()` | `ConversationSummarizer` | None |

---

## 3. Inference Path Map

### Path A — Text chat (primary)
```
sendMessage() → HybridOrchestrator.executeStream() → RuntimeRouter
  → LocalLlamaBackend.generateStream() → LlamaManager.generateStream() → JNI
  OR CloudBackend.generateStream() → RemoteModelExecutor → HTTP
```

### Path B — Vision (bypass)
```
sendMessageWithImage() → llamaManager.generateWithImage() → JNI
[No HybridOrchestrator. No privacy gate. Documented LOCAL-only.]
```

### Path C — Tool follow-up synthesis (bypass)
```
handleToolIfNeeded() → [tool executes] → llamaManager.generate() → JNI
[No HybridOrchestrator.]
```

### Path D — Conversation summary (bypass)
```
ConversationSummarizer.summarize() → llamaManager.generate() → JNI
[No HybridOrchestrator. Background-only, not user-facing.]
```

---

## 4. Reachability Analysis

### LocalLlamaBackend
- **Constructor call**: `ChatViewModel.kt:329`
- **Stored in**: `RuntimeRouter` (passed as constructor arg)
- **Called by**: `RuntimeRouter.route()` inside `HybridOrchestrator.executeStream()`
- **Also reached directly** via `LlamaManager` in Paths B, C, D above

### CloudBackend
- **Constructor call**: `ChatViewModel.kt:330`
- **Stored in**: `RuntimeRouter`
- **Called by**: `RuntimeRouter.route()` when `ExecMode != LOCAL_ONLY`

### HybridOrchestrator
- **Constructor**: `ChatViewModel.kt:336`
- **Live call sites** (non-comment): lines 634, 742, 779, 936, 1645, 1677
- **Also wired into**: `cognitiveLoop.orchestratorProvider` (line 741) and `engine.llmPlanner` (line 779)

### AccessibilityExecutionEngine
- **Instantiated**: `AiriAccessibilityService.kt:62` and `ServiceLocator.kt:342`
- **Held by**: `AndroidAgent.engine` (internal field)
- **Called by**: `AndroidAgent.execute()` via `engine.executeTask()`
- **Reached via**: `SubAgentRegistry.route()` → `ProductionAgentOrchestrator.executeSingle()` → `AndroidAgent`
- **Requires**: AiriAccessibilityService enabled AND AndroidAgent keyword-matched

### ToolDispatcher
- **Constructor**: `ChatViewModel.kt:362`
- **Called by**: `AgentLoop.execute()` (line 148 in AgentLoop.kt)
- **Reached via**: `agentLoop.run()` called from `sendMessage()` when `queryType == QueryType.ACTION && !wasToolCall`

### AgentLoop
- **Constructor**: `ChatViewModel.kt:365`
- **Called by**: `sendMessage()` at line 1511
- **Condition**: `queryType == QueryType.ACTION && !wasToolCall` — runs AFTER HybridOrchestrator has already produced and displayed a response

---

## 5. Tool-Calling Loop: Code Evidence

### Q: Does the LLM receive tool schemas?
**YES.** `AgentLoop.kt:88`:
```kotlin
val fullSystemPrompt = systemPrompt + "\n\n" + buildToolBlock(tools) + TOOL_CALL_INSTRUCTION
```
`buildToolBlock()` iterates `BuiltinTools.ALL` (13 tools) and appends name + description + parameters.

### Q: Does the LLM emit structured tool calls?
**CANNOT BE VERIFIED IN CODE.** The instruction injected is:
```
When you need to use a tool, respond ONLY with this exact JSON:
{"tool_call":{"name":"<tool_name>","args":{"<param>":"<value>"}}}
```
Whether the LLM actually follows this instruction depends on model capability. The system uses **prompt-engineering** to elicit structured output, NOT grammar-constrained generation (GBNF) and NOT a native tool API. Compliance is model-dependent.

### Q: Are tool results fed back into the next model turn?
**YES.** `AgentLoop.kt:165-166`:
```kotlin
history.add(ConversationTurn.Assistant(rawResponse))
history.add(ConversationTurn.ToolResult(toolName, resultText))
```
The next `callLLM()` call includes the full history, formatted as:
```
[Tool calendar_read returned: ...]
```

### Q: Is execution iterative?
**YES.** `AgentLoop.kt:98`:
```kotlin
while (stepsUsed < MAX_STEPS && coroutineContext.isActive) {
```
MAX_STEPS = 12. TIMEOUT_MS = 60,000.

### Q: Critical limitation — when does AgentLoop actually run?
AgentLoop runs **AFTER** `HybridOrchestrator.executeStream()` has **already completed** and the response is **already shown to the user**. The sequence is:
1. LLM produces a free-text response (shown in chat)
2. If `queryType == ACTION && !wasToolCall`: AgentLoop fires in a separate coroutine
3. AgentLoop calls the LLM AGAIN with tool schemas

This means the LLM does not plan using tool schemas on the FIRST turn. Tool schemas only appear in the **second-pass** AgentLoop call. The initial query to the LLM has no tool schemas — it sees the user's message and the system prompt with `ACTION_PLAN_SUFFIX` (a legacy JSON plan format, not AgentLoop's `{"tool_call":...}` format).

---

## 6. Regex Planning Status

### Q: Is regex planning still present?
**YES — in `AccessibilityExecutionEngine.heuristicNextAction()`** (lines 345-370):
```kotlin
val openMatch  = Regex("""(?:open|launch|start)\s+...""").find(lower)
val tapMatch   = Regex("""(?:tap|click|press|select)...""").find(lower)
val typeMatch  = Regex("""(?:type|enter|write)...""").find(lower)
val searchMatch = Regex("""(?:search|find)...""").find(lower)
```
This is the **fallback** path when `llmPlanner` returns null or throws. The `llmPlanner` field is wired from ChatViewModel (line 779), so on a successfully initialized session, the LLM path runs first and falls back to regex only on error.

### Q: Is createDAGPlanFromLLM still present?
**YES.** `PlanGenerator.kt:59`. It is called from `UnifiedCognitiveLoop.processPercept()` (line 361). UCL is NOT called from `sendMessage()` — its only connection to ChatViewModel is `cognitiveLoop.orchestratorProvider` which is used when UCL is invoked via other paths (ResearchAgent, etc.). UCL's `executeGraph()` is **not called from `sendMessage()`** in the current code.

### Q: Is JSON scraping from free text present?
**YES.** `createDAGPlanFromLLM()` scrapes JSON from the LLM's free text using `JSONObject` parsing on substrings. However, this function is called from `UCL.processPercept()`, which is called by `UCL.process()`, which has no direct caller in `sendMessage()`.

The `toTypedPlanGraph` import remains in `ChatViewModel.kt:103` but **no call site uses it** — it is an unused import.

### Q: Is SubAgentRegistry still keyword-routed?
**YES.** `SubAgentRegistry.kt:134-165`: keyword scoring via `intentKeywords.any { kw -> normalized.contains(kw) }`. Domain boost applied. No LLM involvement in routing decisions.

---

## 7. Complete Dead File List

| File | LOC | Caller count | Reason dead |
|---|---|---|---|
| `agent/planning/ActionPlanExtensions.kt` | 178 | 0 live | `toTypedPlanGraph` imported in ChatViewModel but no call site; UCL.executeGraph not called from sendMessage |
| `voice/LiveVoiceService.kt` | 431 | ChatViewModel (comment only), PerceptionFusion (comment) | `android:enabled="false"` in manifest; no `startService()` call anywhere |
| `voice/LiveVoiceSession.kt` | 280 | LiveVoiceService only | Only used by disabled LiveVoiceService |
| `voice/VoiceAgentRouter.kt` | 223 | LiveVoiceService only | Only used by disabled LiveVoiceService |
| `voice/realtime/GeminiLiveProvider.kt` | 309 | LiveVoiceService only | Only used by disabled service |
| `voice/realtime/OpenAIRealtimeProvider.kt` | 335 | LiveVoiceService only | Only used by disabled service |
| `voice/realtime/RealtimeVoiceProvider.kt` | 179 | LiveVoiceService only | Only used by disabled service |
| `voice/IncrementalTtsEngine.kt` | 127 | LiveVoiceService only | Only used by disabled service |
| `voice/DuplexConversationRuntime.kt` | 102 | 0 | Nothing instantiates it |
| `voice/FullDuplexVadEngine.kt` | 413 | VoiceManager | **EXCEPTION: VoiceManager IS live** (ChatScreen instantiates it). This file is **not dead** — it is used by the Vosk STT path. |

**Corrected dead file list (voice/FullDuplexVadEngine.kt removed — it has a real live caller):**

| File | LOC | Dead reason |
|---|---|---|
| `agent/planning/ActionPlanExtensions.kt` | 178 | Unused import in ChatViewModel; no call site |
| `voice/LiveVoiceService.kt` | 431 | `android:enabled="false"`; no startService() callers |
| `voice/LiveVoiceSession.kt` | 280 | Only used by disabled LiveVoiceService |
| `voice/VoiceAgentRouter.kt` | 223 | Only used by disabled LiveVoiceService |
| `voice/realtime/GeminiLiveProvider.kt` | 309 | Only used by disabled service |
| `voice/realtime/OpenAIRealtimeProvider.kt` | 335 | Only used by disabled service |
| `voice/realtime/RealtimeVoiceProvider.kt` | 179 | Only used by disabled service |
| `voice/IncrementalTtsEngine.kt` | 127 | Only used by disabled service |
| `voice/DuplexConversationRuntime.kt` | 102 | Zero callers |
| **Total** | **2,164 LOC** | |

---

## 8. 20 Largest Files

| Rank | LOC | File |
|---|---|---|
| 1 | 2,899 | `ui/viewmodel/ChatViewModel.kt` |
| 2 | 2,426 | `ui/screens/ModelSettingsScreen.kt` |
| 3 | 2,395 | `ui/screens/ChatScreen.kt` |
| 4 | 1,973 | `ai/LlamaManager.kt` |
| 5 | 980 | `ui/screens/ExecDiagnosticsScreen.kt` |
| 6 | 849 | `ui/screens/SkillBuilderScreen.kt` |
| 7 | 761 | `core/VoiceManager.kt` |
| 8 | 692 | `ui/screens/ModelLibraryScreen.kt` |
| 9 | 663 | `ui/screens/CloudModelStore.kt` |
| 10 | 635 | `ui/screens/ObservabilityScreen.kt` |
| 11 | 627 | `ui/screens/IntegrationsScreen.kt` |
| 12 | 589 | `agent/orchestrator/ProductionAgentOrchestrator.kt` |
| 13 | 575 | `accessibility/execution/AccessibilityExecutionEngine.kt` |
| 14 | 561 | `core/UnifiedCognitiveLoop.kt` |
| 15 | 519 | `ui/screens/PerformanceScreen.kt` |
| 16 | 518 | `agent/planning/PlanGenerator.kt` |
| 17 | 510 | `ui/screens/SkillManagerScreen.kt` |
| 18 | 506 | `ui/screens/RuntimeDiagnosticsPanel.kt` |
| 19 | 501 | `ui/AiriApp.kt` |
| 20 | 483 | `ui/screens/PaywallScreen.kt` |

---

## 9. 20 Largest Classes in Active Runtime

| LOC | Class | Role in runtime |
|---|---|---|
| 2,899 | `ChatViewModel` | All user-facing state management |
| 2,426 | `ModelSettingsScreen` | Model config UI |
| 2,395 | `ChatScreen` | Primary chat UI |
| 1,973 | `LlamaManager` | Local inference JNI bridge |
| 761 | `VoiceManager` | Vosk STT pipeline |
| 589 | `ProductionAgentOrchestrator` | Sub-agent execution orchestration |
| 575 | `AccessibilityExecutionEngine` | Android UI automation |
| 561 | `UnifiedCognitiveLoop` | DAG execution engine (called via orchestratorProvider) |
| 518 | `PlanGenerator` | Creates action plans from LLM output (called inside UCL) |
| 420 | `ServiceLocator` | DI container |
| 408 | `ModelController` | Model lifecycle (extracted from ChatViewModel) |
| 343 | `HybridOrchestrator` | Inference routing + privacy gate |
| 310 | `AgentLoop` | Iterative tool-calling loop (new) |
| 258 | `AndroidAgent` | Accessibility sub-agent |
| 254 | `ToolDispatcher` | Tool execution dispatch (new) |
| 231 | `SkillService` | Old keyword-parsed tool dispatch |
| 205 | `SubAgentRegistry` | Sub-agent routing (keyword-scored) |
| 189 | `ResearchAgent` | Web search sub-agent |
| 122 | `RuntimeRouter` | Backend selection (local vs cloud) |
| 110 | `ScheduledAgentWorker` | WorkManager background agent dispatch |

---

## 10. ChatViewModel Responsibilities

ChatViewModel is a single class owning **all** of the following:

1. **31 MutableStateFlows** — streaming text, messages list, agent state, model state, sessions, debug state, paywall, voice state, performance mode, execution mode, privacy level, smart replies, stall hints, token rate history, exec diagnostics, and more
2. **Inference routing** — calls HybridOrchestrator, wires cognitiveLoop.orchestratorProvider, wires engine.llmPlanner
3. **Session management** — loadInitialSession, createNewSession, loadSession, deleteSession
4. **Model lifecycle delegation** — all model calls delegated to ModelController (extracted this refactor)
5. **Sub-agent routing** — builds SubAgentContext, calls SubAgentRegistry.route()
6. **Tool dispatch** — handleToolIfNeeded(), AgentLoop wiring
7. **Memory/RAG injection** — semanticSearch(), MemoryExtractor integration
8. **Vision/attachment routing** — sendMessageWithImage(), sendMessageWithAttachments()
9. **Cloud provider management** — activateBuiltinProvider(), refreshCloudReadiness(), clearCloudModel()
10. **Voice glue** — observeVoiceTranscriptBus(), updateVoiceState()
11. **Accessibility gate** — confirmAccessibilityAction(), awaitAccessibilityConfirmation()
12. **Performance/diagnostics** — observeExecutionStatusBus(), runDiagnostics(), observeMemoryPressureBus()
13. **Paywall/monetization** — upgradeToPremium(), downgradeToFree(), getSubscriptionSummary()
14. **Settings/preferences** — setExecutionMode(), setPrivacyLevel(), setPerformanceMode(), setTemperature(), etc.
15. **UI helpers** — clearMessages(), prefillInput(), deleteMessage(), setAgentMode()

**66 public functions, 15 private functions, 2,899 LOC.**

---

## 11. Classification

**Which best describes AIRI?**

### Evidence for each category:

**Chat application** — TRUE. The primary user path is: user types → LLM responds → text shown. This works without any agent, tool, or accessibility infrastructure.

**AI assistant** — TRUE. Memory/RAG pipeline injects context. Sub-agents handle calendar, search, notes, alarms.

**Automation assistant** — PARTIALLY TRUE. AccessibilityExecutionEngine can automate Android UI. It runs when `SubAgentRegistry.route()` matches the "airi_accessibility_enabled" capability AND the keyword score is high enough. The LLM does NOT decide to use accessibility — a keyword matcher decides.

**Agent framework** — PARTIALLY TRUE. `AgentLoop` exists and runs iteratively. `ToolDispatcher` dispatches to 13 real tools. However, the loop runs AFTER the LLM has already answered, not as the primary reasoning path.

**Autonomous agent runtime** — NOT TRUE. The LLM does not control the execution path. A keyword classifier (`QueryClassifier`) decides whether to invoke `AgentLoop`. A keyword scorer (`SubAgentRegistry`) decides which sub-agent runs. The LLM sees tool schemas only in the second-pass `AgentLoop` call, not on the initial user message. The system cannot decide autonomously to observe the screen, use a tool, or take an action without a keyword match first routing it there.

### Verdict

**AIRI is best classified as: AI assistant with automation capabilities.**

It is not an autonomous agent runtime because:
- The LLM does not control the routing path (keyword classifiers do)
- The LLM does not see tool schemas on the primary inference turn
- Tool invocation is a post-response secondary loop, not the primary planning mechanism
- Accessibility automation requires keyword classification to reach AndroidAgent first
- No goal persistence, no background autonomous operation without an active chat session
