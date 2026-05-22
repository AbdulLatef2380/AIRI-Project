# AIRI Dead Systems Report
*Phase H1 — Static analysis. No runtime profiling available.*

---

## Systems That Are Dead or Partially Dead

### 1. ScheduledAgentWorker — WAS MISSING (FIXED THIS SESSION)
**Previous state**: `ScheduledJobOrchestrator.scheduleOnce()` called
`OneTimeWorkRequestBuilder<ScheduledAgentWorker>()` but `ScheduledAgentWorker.kt`
did not exist. Every scheduled task enqueued via `AgentTasksScreen` would trigger
a WorkManager "Worker class not found" crash silently at runtime.

**Current state**: `ScheduledAgentWorker.kt` created. Routes via
`SubAgentRegistry.route()` → `SubAgent.execute()`, falls back to
`ProductionAgentOrchestrator.executeSingle()`. **Gap is closed.**

---

### 2. ReActPlanner — INITIALIZED, NEVER CALLED
- Registered: `ServiceLocator.reActPlanner` (lazy, created in `AIRIApplication.onCreate()`)
- Usage in ChatViewModel: **ZERO**. The planner is instantiated but nothing calls `plan()`.
- The chat execution path goes: `SubAgentRegistry.route()` → `ProductionAgentOrchestrator.executeSingle()` — bypassing `ReActPlanner` entirely.
- **Status**: Dead. Not harmful — it's just wasted init time.

---

### 3. CoTEngine — INITIALIZED, NEVER CALLED FROM CHAT PATH
- Registered: `ServiceLocator.cotEngine`
- `CoTEngine` is a dependency of `ReActPlanner` — since `ReActPlanner` is not called, `CoTEngine` also never executes during normal chat.
- **Status**: Conditionally dead (would activate if ReActPlanner were wired in).

---

### 4. PlannerAdaptationEngine — INITIALIZED, NO CALLERS
- Registered: `ServiceLocator.plannerAdaptationEngine`
- `ChatViewModel` does not call it. `ProductionAgentOrchestrator` does not call it.
- **Status**: Dead.

---

### 5. AdaptiveIntelligenceEngine — INITIALIZED, NO CONFIRMED CALLERS
- Registered: `ServiceLocator.adaptiveIntelligence`
- No callers found in `ChatViewModel`, orchestrators, or SubAgents.
- **Status**: Dead (possibly called from `SkillOutcomeScorer` — not confirmed).

---

### 6. AppEvent.OAuthCallbackReceived — EMITTED, NOT SUBSCRIBED
- `MainActivity.onNewIntent` now emits this event (added this session).
- `IntegrationsViewModel` has no `EventBus.events.collect` subscriber for it.
- GitHub and Telegram use token-paste flows and do not need it.
- **Status**: Forward-compatible stub. Not dead but not yet consumed.

---

### 7. AgentEventStream — WRITTEN, NO UI SUBSCRIBER CONFIRMED
- `GlobalAgentEventDispatcher` feeds `AgentEventStream`.
- The `ObservabilityScreen` likely subscribes to it but this was not confirmed via static analysis.
- **Status**: Potentially dead if `ObservabilityScreen` is not open.

---

### 8. ModelGovernanceEngine — INITIALIZED, ROUTING BYPASSES IT
- `ChatViewModel` uses `execModePrefs.preferredProvider` directly for routing decisions.
- `ModelGovernanceEngine` has `evaluate(context, request)` API that is never called.
- **Status**: Dead in production chat path.

---

### 9. DurableTaskManager — REFERENCED BY ExecutionGraphRuntime, NOT USED IN PRACTICE
- `ExecutionGraphRuntime` holds a reference to `DurableTaskManager`.
- No user-visible feature creates durable tasks through the UI.
- **Status**: Standby — backend infrastructure without frontend activation.

---

### 10. SkillOutcomeScorer — INITIALIZED, EXECUTION UNKNOWN
- `ServiceLocator.skillOutcomeScorer` is registered.
- `SkillRuntime` may call it — not confirmed without full trace of `SkillRuntime.execute()`.
- **Status**: Unconfirmed.

---

## Systems That Are Correctly Active

All confirmed via code trace:
- `HybridOrchestrator` → every chat message
- `LocalLlamaBackend` (JNI) → local model generation
- `CloudBackend` + `RetryPolicy` → cloud generation
- `SubAgentRegistry` → agent-mode routing
- `ProductionAgentOrchestrator` → multi-step execution
- `MemoryManager` + `RagRetriever` → context enrichment
- `TokenAccountant` → usage tracking
- `RuntimeHealthMonitor` → health loop (60s interval)
- `ExecutionWatchdog` → stuck plan detection
- `ConnectorRegistry` + `ConnectorHealthMonitor` → connector state
- `VoskEngine` + `VoiceTranscriptBus` → voice pipeline
- `EventBus` → cross-layer events
- `ScheduledJobOrchestrator` → task persistence + WorkManager enqueue
- `ScheduledAgentWorker` → task execution (**new this session**)
- `ThemePreferences` → live theme mode
- `AgentWorker` (2h periodic) → background GitHub/Gmail checks

---

## Recommendation

The dead systems are not causing production failures. They represent:
1. **Architecture scaffolding** built ahead of active use (`ReActPlanner`, `CoTEngine`, `PlannerAdaptationEngine`)
2. **Governance layers** bypassed by the current routing logic (`ModelGovernanceEngine`)
3. **Forward-compatible infrastructure** waiting for callers (`OAuthCallbackReceived`)

None should be deleted yet — they are architected correctly and will be needed as the product matures.
