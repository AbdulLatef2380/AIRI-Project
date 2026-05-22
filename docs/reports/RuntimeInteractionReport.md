# AIRI Runtime Interaction Report
*Phase H1 — Generated from static analysis of actual source tree*

---

## Runtime Execution Flow

```
User Input (ChatScreen)
  └─► ChatViewModel.sendMessage()
        ├─► buildSystemPrompt()          [RAG + SubAgentCapabilities + MemoryManager]
        ├─► HybridOrchestrator.execute() [Mutex-serialized, privacy gate]
        │     ├─► NetworkGuard.evaluate()
        │     ├─► RuntimeRouter.route()  [LOCAL / CLOUD / HYBRID decision]
        │     │     ├─► LocalLlamaBackend  (JNI → llama.cpp)
        │     │     └─► CloudBackend       (RetryPolicy → CloudAdapterFactory → provider HTTP)
        │     └─► streaming tokens → _streamingText StateFlow → ChatScreen
        ├─► SubAgentRegistry.route()     [if agentMode == AGENT]
        │     └─► SubAgent.execute() → Flow<AgentEvent>
        ├─► productionOrchestrator.executeSingle() [multi-step plans]
        └─► tokenAccountant.recordSuccess() → _todayTokens StateFlow → TopBar badge
```

---

## Systems and Their Real Execution Participation

### ✅ FULLY PARTICIPATING IN EXECUTION

| System | How it participates |
|---|---|
| `HybridOrchestrator` | Every chat message goes through it |
| `LocalLlamaBackend` | JNI calls to llama.cpp on LOCAL mode |
| `CloudBackend` | HTTP streaming to cloud providers |
| `RetryPolicy` | Wraps all CloudBackend streaming attempts |
| `NetworkGuard` | Pre-flight check before every cloud call |
| `TokenAccountant` | Records real prompt+completion tokens per provider |
| `MemoryManager` | RAG context built for every non-trivial query |
| `RagRetriever` | Builds memory context blocks (k=4 hits) |
| `SubAgentRegistry` | Routes agent-mode messages to correct sub-agent |
| `ProductionAgentOrchestrator` | Multi-step plan execution |
| `RuntimeHealthMonitor` | Running loop — heap/disk/network/coroutine checks |
| `ExecutionWatchdog` | Running loop — stuck plan detection |
| `ConnectorRegistry` | Initialized at startup; used by ConnectorsScreen |
| `ConnectorHealthMonitor` | Background ping loop for all connectors |
| `ScheduledJobOrchestrator` | Persists and enqueues WorkManager jobs |
| `ScheduledAgentWorker` | **CREATED THIS SESSION** — executes scheduled jobs |
| `EventBus` | Cross-layer event routing |
| `AgentEventStream` | Agent activity feed |
| `VoiceTranscriptBus` | STT → chat message bridge |
| `ThemePreferences` | Persists and emits DARK/LIGHT/SYSTEM state |

### ⚠️ REGISTERED BUT PARTICIPATION IS PARTIAL

| System | Gap |
|---|---|
| `AgentWorker` | Runs every 2h via WorkManager; checks GitHub/Gmail. Does NOT call SubAgentRegistry. Operates in parallel to the main execution path — not integrated. |
| `SkillRuntime` | Initialized in ServiceLocator but ChatViewModel calls `skillService` (domain layer) not `skillRuntime` (execution layer) directly |
| `WorkspaceRuntime` | Full implementation; UI entry exists (WorkspaceScreen). No ViewModel-level API bridging it to chat generation output |
| `ReActPlanner` | Initialized; `ChatViewModel` does not call it. Currently dead path. |
| `CoTEngine` | Initialized; not called from production chat flow |
| `PlannerAdaptationEngine` | Initialized; no callers observed in ChatViewModel or orchestrator |
| `AdaptiveIntelligenceEngine` | Initialized; callers not found in UI layer |
| `DurableTaskManager` | Referenced by `ExecutionGraphRuntime` but no user-visible jobs ever enqueued via UI |

### ❌ REGISTERED IN SERVICELOCATOR — NO RUNTIME PARTICIPATION CONFIRMED

| System | Status |
|---|---|
| `CloudBrowserAgent` | Registered in SubAgentRegistry. No accessible UI trigger; web browsing through the agent requires accessibility service — unclear if this ever fires |
| `LocalBrowserOperator` | Same as above |
| `MediaGenerationAgent` | Registered. No confirmed execution path from production chat |
| `ModelGovernanceEngine` | Initialized but ChatViewModel uses `execModePrefs.preferredProvider` directly, not the governance engine |
| `SkillOutcomeScorer` | Initialized; no callers confirmed |

---

## Dead Event Flows

| Flow | Status |
|---|---|
| `AppEvent.OAuthCallbackReceived` | **EMITTED** by MainActivity (added this session). No subscriber in IntegrationsViewModel yet. |
| `AgentEventStream` | Receives events from AgentWorker; no confirmed UI subscriber observing this |
| `voiceTranscriptBus` | Emitted by LiveVoiceService → collected by ChatViewModel. **REAL AND WORKING** |

---

## State Desynchronization Risks

1. **`_todayTokens` vs `tokenAccountant`**: Token counter updates only after cloud success. Local model token usage is not tracked in `todayTokens` (LlamaManager does not emit token counts to `TokenAccountant`). The displayed counter under-counts local usage.

2. **`ScheduledJob.triggerAtMs` vs WorkManager actual fire time**: The persisted `triggerAtMs` is set at enqueue time. WorkManager may defer execution (Doze mode, battery saver). The AgentTasksScreen "pending" vs "completed" tab uses `triggerAtMs` for classification — a job that fired late will still show as "completed" even if the worker hasn't run.

3. **`ConnectorState.connected` vs real connector availability**: `ConnectorHealthMonitor` pings connectors every 30s. Between pings, the UI may show "connected" for a connector that has lost its token.

---

## Runtime Interaction Map (Dependency Tree)

```
AIRIApplication.onCreate()
  ├── RuntimeRecoveryEngine       → UncaughtExceptionHandler
  ├── NetworkService              → ConnectivityManager callback
  ├── SubscriptionManager         → BillingClient
  ├── AiriDatabase                → Room (MemoryManager, ExecutionHistoryStore)
  ├── RuntimeHealthMonitor        → periodic heap/disk/network/coroutine checks
  ├── SubAgentSystem
  │     ├── SubAgentRegistry      ← 9 agents registered
  │     ├── ScopedPermissionRegistry
  │     └── AgentObservabilityHub
  ├── CoT/ReAct planners          → available but not called from chat path
  ├── RagRetriever                → MemoryManager
  ├── ExecutionWatchdog           → ExecutionGraphRuntime → ProductionAgentOrchestrator
  ├── ConnectorHealthMonitor      → ConnectorRegistry (9 connectors)
  └── AgentCapabilityGraph        → multi-agent routing

ChatViewModel (per-screen lifetime)
  ├── HybridOrchestrator          → RuntimeRouter → Local/Cloud
  ├── SubAgentRegistry            → route() on AGENT mode
  ├── ProductionAgentOrchestrator → executeSingle() on complex plans
  ├── RagRetriever                → buildContextBlock() per message
  ├── MemoryManager               → session save
  ├── TokenAccountant             → recordSuccess() → todayTokens StateFlow
  ├── VoskEngine (STT)            → via VoiceTranscriptBus
  └── RuntimeHealthMonitor        → health StateFlow exposed to UI
```
