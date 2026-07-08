# AIRI ACTIVATION PLAN — PART 2
## Connectors · UX · Feature Completion · Memory Pipeline
**Wave 2 + Wave 3 items | P1–P2 | Days 8–21**

> Builds on Part 1 (security baseline complete). AP-04, AP-06, AP-08 must complete before this part begins.

---

## AP-12 — RUNTIME PROFILER UI EXPOSURE

### Current State
**Status:** Backend Only. `RuntimeProfiler` collects data. Zero UI consumers.

**Why Not Active:**
`RuntimeProfiler.kt` tracks inference latency histograms (P50/P90/P99), JNI backpressure events, event drop rates, and slow calls (>500ms). Its only consumer is `ReleaseReadinessReport`, which is manually triggered and not part of the live UI. `DeveloperCenterScreen` has 6 tabs — none source `RuntimeProfiler`. All profiling data is collected and discarded from a user perspective.

### Activation Path
```
runtime/RuntimeProfiler.kt — verify or add reportFlow: StateFlow<ProfilerReport>
    ↓
ServiceLocator.kt — wire runtimeProfiler as singleton (if not already)
    ↓
ui/DeveloperCenterScreen.kt — add Tab 7 "Profiler"
    ↓
Profiler tab: render P50/P90/P99, JNI backpressure, event drop rate, slow call list
    ↓
Gate tab behind: BuildConfig.DEBUG || debugMode
    ↓
Manual test: open DeveloperCenter Profiler tab — data refreshes every 5s
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `runtime/RuntimeProfiler.kt` | Add `reportFlow: StateFlow<ProfilerReport>` if not already present; add 5s refresh ticker |
| `ServiceLocator.kt` | `val runtimeProfiler: RuntimeProfiler by lazy { RuntimeProfiler() }` if not registered |
| `ui/DeveloperCenterScreen.kt` | Add Tab 7 "Profiler"; render `ProfilerReport`; gate behind debug flag |

### Exact Tab Implementation
```kotlin
// DeveloperCenterScreen.kt — add to tabs list:
val tabs = listOf("Overview", "Logs", "Connectors", "Memory", "Security", "Updates", "Profiler")

// Profiler tab content:
7 -> {
    val isDebugMode by agentViewModel.debugMode.collectAsState()
    if (!BuildConfig.DEBUG && !isDebugMode) {
        Text("Enable debug mode in AgentControlScreen to view profiler data")
        return@tabContent
    }
    val report by ServiceLocator.runtimeProfiler.reportFlow.collectAsState()
    ProfilerCard("Inference Latency") {
        Column {
            Text("P50: ${report.p50Ms}ms")
            Text("P90: ${report.p90Ms}ms")
            Text("P99: ${report.p99Ms}ms")
        }
    }
    ProfilerCard("JNI Backpressure") {
        Text("${report.jniBackpressureCount} events since app start")
    }
    ProfilerCard("Event Drop Rate") {
        Text("${report.dropRatePct}%")
    }
    if (report.slowCalls.isNotEmpty()) {
        ProfilerCard("Slow Calls (>500ms)") {
            report.slowCalls.take(20).forEach { call ->
                Row {
                    Text(call.name, modifier = Modifier.weight(1f))
                    Text("${call.durationMs}ms")
                }
            }
        }
    }
}
```

### Dependency Activation Graph
```
AP-12 Activated
    ↓
AP-13 (FlowPressureMonitor): backpressure events now surface in Profiler tab
    ↓
DeveloperCenterScreen is now complete (7 tabs, all data sources represented)
    ↓
Engineers can identify slow inference calls, JNI pressure, and event drops in real time
    ↓
ReleaseReadinessReport now has a live companion view
```

### Ripple Effect
**3 files** modified. No new classes if `ProfilerReport` data class already exists in `RuntimeProfiler.kt`.

### Testing Strategy
```
1. Open DeveloperCenterScreen → Profiler tab → data displayed
2. Profiler tab hidden in production build when debugMode = false
3. Trigger a known-slow operation → verify it appears in slow call list
4. Data refreshes within 5s of new profiling events
5. JNI backpressure counter increments when LLM inference is overloaded
```

### Definition of Done
- [ ] `RuntimeProfiler.reportFlow: StateFlow<ProfilerReport>` present and emitting
- [ ] Tab 7 "Profiler" in DeveloperCenterScreen renders P50/P90/P99, backpressure, drop rate, slow calls
- [ ] Tab gated behind `BuildConfig.DEBUG || debugMode`
- [ ] Data refreshes live (5s polling or StateFlow-driven)

---

## AP-13 — FLOW PRESSURE MONITOR WIRING

### Current State
**Status:** Orphaned. `FlowPressureMonitor` fully implemented. Zero production callers.

**Why Not Active:**
`AgentActivityBus.events` (SharedFlow) and `ExecutionStatusBus.state` are not wrapped with `FlowPressureMonitor`. Slow collectors (e.g., a heavy `ChatScreen` recomposition blocking the main thread) drop events silently with no logging, no alerting, and no diagnostic data. `FlowPressureMonitor` exists precisely for this purpose but is never invoked.

### Activation Path
```
core/AgentActivityBus.kt — wrap events with FlowPressureMonitor.monitor()
    ↓
core/ExecutionStatusBus.kt — wrap state with FlowPressureMonitor.monitor()
    ↓
FlowPressureMonitor callback — route backpressure events to AuditRepository
    ↓
AP-12 (Profiler tab) — backpressure events surface in DeveloperCenterScreen
    ↓
Unit test: slow collector → backpressure logged to AuditRepository
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `core/AgentActivityBus.kt` | Wrap `events` with `FlowPressureMonitor.monitor("AgentActivityBus")` |
| `core/ExecutionStatusBus.kt` | Wrap `state` with `FlowPressureMonitor.monitor("ExecutionStatusBus")` |

### Exact Changes
```kotlin
// AgentActivityBus.kt:
val events: SharedFlow<ActivityEvent> = _events
    .let { FlowPressureMonitor.monitor(it, label = "AgentActivityBus") { dropCount ->
        // Callback fires when events are dropped
        ServiceLocator.auditRepository.logSync(
            tag = "FLOW_BACKPRESSURE",
            message = "AgentActivityBus: $dropCount events dropped",
            level = LogLevel.WARN
        )
    }}

// ExecutionStatusBus.kt:
val state: SharedFlow<ExecutionEvent> = _state
    .let { FlowPressureMonitor.monitor(it, label = "ExecutionStatusBus") { dropCount ->
        ServiceLocator.auditRepository.logSync(
            tag = "FLOW_BACKPRESSURE",
            message = "ExecutionStatusBus: $dropCount events dropped",
            level = LogLevel.WARN
        )
    }}
```

### Dependency Activation Graph
```
AP-13 Activated
    ↓
AgentActivityBus backpressure now visible in AuditRepository (tag: FLOW_BACKPRESSURE)
    ↓
AP-12 (Profiler tab) — backpressure events appear in DeveloperCenter
    ↓
Slow collector diagnosis is now possible in production (via audit log)
    ↓
Agent execution event reliability is now measurable
```

### Ripple Effect
**2 files** modified. The `FlowPressureMonitor.monitor()` call is additive — it wraps the existing SharedFlow; all existing collectors continue to work unchanged.

### Testing Strategy
```
Unit test:
1. Create a flow → wrap with FlowPressureMonitor.monitor()
2. Simulate a slow collector (delay in collect block)
3. Emit values faster than collector can process
4. Verify AuditRepository receives FLOW_BACKPRESSURE log entry with correct drop count

Regression:
5. All existing ChatScreen, AgentPlanViewModel, DeveloperCenterScreen collectors still receive events
6. No additional latency introduced in the non-backpressure case
```

### Definition of Done
- [ ] `AgentActivityBus.events` wrapped with `FlowPressureMonitor`
- [ ] `ExecutionStatusBus.state` wrapped with `FlowPressureMonitor`
- [ ] Backpressure events logged to `AuditRepository` with tag `FLOW_BACKPRESSURE`
- [ ] Existing event collectors unaffected (regression test passes)

---

## AP-14 / AP-15 — DEAD CODE REMOVAL (Agent Decision + Planning Chain)

> **Wave note:** Blueprint places A-14/A-15 in Wave 4. They are moved to Part 2 (Wave 3 execution window) because they are pure deletions with zero risk of regression and no dependency on any other activation item. Executing them earlier shrinks the codebase before the larger Wave 3 feature work begins, reducing noise in code review and static analysis.

### Current State
**Status:** Dead. ~3,400 lines of zero-caller code in `agent/decision/` and `agent/planning/` that compile but have zero production effect.

### Why Not Removed
No one confirmed zero callers and executed the deletion. The code exists as a maintenance burden: it confuses new contributors, inflates build time, and creates false impressions of active capability.

### Dead Classes to Delete

**`agent/decision/` — 9 files:**
`AdaptiveBehaviorEngine.kt`, `AdaptiveDecisionEngine.kt`, `BehaviorPolicy.kt`, `ConfidenceScorer.kt`, `DecisionEngine.kt`, `EmotionEngine.kt`, `DialogueRhythmEngine.kt`, `RelationshipBoundaryPolicy.kt`, `SuggestionScoreEngine.kt`

**`agent/multiagent/` — 2 files:**
`TaskOrchestrator.kt`, `AgentTaskDelegator.kt`

**`agent/planning/` — 6 files:**
`BrainManager.kt`, `AiriBrainController.kt`, `GoalExecutor.kt`, `IntentEngine.kt`, `ActionPlanner.kt`, `ReActPlanner.kt`

**`agent/execution/runtime/` — 1 file:**
`AgentExecutor.kt`

**`world/` — 1 file (separate item):**
`SensoryBudgetManager.kt`

**`domain/` — 1 file:**
`AgentService.kt`

### Activation Path (for each batch)
```
Step 1 — Verify zero callers (REQUIRED before deletion):
grep -r "AdaptiveBehaviorEngine|AdaptiveDecisionEngine|BehaviorPolicy|ConfidenceScorer|DecisionEngine|EmotionEngine|DialogueRhythmEngine|RelationshipBoundaryPolicy|SuggestionScoreEngine|TaskOrchestrator|AgentTaskDelegator" \
    --include="*.kt" app/src/ | grep -v "class \|object \|interface "
→ Must return zero matches outside own files

Step 2 — Delete the 11 agent/decision/ + multiagent/ files

Step 3 — Full build → zero compile errors → confirm

Step 4 — Verify zero callers for planning chain:
grep -r "BrainManager|AiriBrainController|GoalExecutor|IntentEngine|ActionPlanner|ReActPlanner|AgentExecutor" \
    --include="*.kt" app/src/ | grep -v "class \|object \|interface "
→ Must return zero matches

Step 5 — Delete the 7 planning chain files

Step 6 — Full build → zero compile errors → confirm

Classes to KEEP (in same packages):
    agent/decision/: PatternAggregator.kt, GuardianEngine.kt, RiskProvider.kt
    agent/planning/: PlanGenerator.kt, TypedPlanGraph.kt, CoTEngine.kt, RecoveryManager.kt
    agent/execution/runtime/: ExecutionGraphRuntime.kt (Phase 9), AdaptiveGraphEngine.kt (Phase 9)
```

### Exact Verification Commands
```bash
# Batch 1 — decision + multiagent:
grep -rn "AdaptiveBehaviorEngine\|AdaptiveDecisionEngine\|BehaviorPolicy\|ConfidenceScorer\|DecisionEngine\|EmotionEngine\|DialogueRhythmEngine\|RelationshipBoundaryPolicy\|SuggestionScoreEngine\|TaskOrchestrator\|AgentTaskDelegator" \
    app/src/main/java --include="*.kt" \
    | grep -v "class \|object \|interface \|//"

# Batch 2 — planning chain:
grep -rn "BrainManager\|AiriBrainController\|GoalExecutor\|IntentEngine\|ActionPlanner\|ReActPlanner\|AgentExecutor" \
    app/src/main/java --include="*.kt" \
    | grep -v "class \|object \|interface \|//"

# Both must return zero results before deletion proceeds.
```

### Dependency Activation Graph
```
AP-14/15 Activated (dead code removed)
    ↓
~3,700 lines eliminated
    ↓
agent/decision/ contains only 3 active classes (down from 12)
    ↓
agent/planning/ contains only 4 active classes (down from 10)
    ↓
Build time reduction
    ↓
Codebase clarity: new contributors are not confused by dead architecture
    ↓
Static analysis tools report fewer false positives
```

### Ripple Effect
**20 files deleted total.** Zero files modified. Zero tests should break (dead code has no active test coverage by definition — verify this first).

### Testing Strategy
```
Before deletion:
1. Run all existing tests → establish baseline pass count
2. Run grep verification → confirm zero callers for each batch

After deletion:
3. Full build → zero compile errors → REQUIRED
4. All tests pass at same count as baseline → REQUIRED
5. Run a full chat interaction end-to-end → no regression
```

### Rollback Strategy
`git restore` the deleted files. No data changes.

### Definition of Done
- [ ] 11 `agent/decision/` and `agent/multiagent/` files deleted; `agent/decision/` retains 3 active classes
- [ ] 7 `agent/planning/` and `agent/execution/runtime/` files deleted; `agent/planning/` retains 4 active classes
- [ ] Full build: zero compile errors after each batch deletion
- [ ] All existing tests pass post-deletion

---

## AP-17 — CAMERA JPEG CACHE CLEANUP

### Current State
**Status:** Architecture Only. Camera captures accumulate indefinitely in `cacheDir/chat_attachments/`.

**Why Not Active:**
`ChatViewModel.sendMessage()` (line 2176) saves camera captures to `cacheDir/chat_attachments/${filename}.jpg`. This path is never cleaned. `AIRIApplication.onTrimMemory()` has the correct hook available but does not clean this directory. Heavy camera users accumulate hundreds of MB.

### Activation Path
```
ui/viewmodel/ChatViewModel.kt — add cleanup in createNewSession()
    ↓
AIRIApplication.kt — add cleanup in onTrimMemory() at TRIM_MEMORY_UI_HIDDEN level
    ↓
Manual test: heavy camera use → session end → cache cleared
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/ChatViewModel.kt` | In `createNewSession()`: `context.cacheDir.resolve("chat_attachments").deleteRecursively()` |
| `AIRIApplication.kt` | In `onTrimMemory()` at `TRIM_MEMORY_UI_HIDDEN`: same delete |

### Exact Changes
```kotlin
// ChatViewModel.kt — in createNewSession():
fun createNewSession() {
    // ADD at start:
    context.cacheDir.resolve("chat_attachments").deleteRecursively()
    // ... existing session creation logic unchanged
}

// AIRIApplication.kt — in onTrimMemory():
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
        cacheDir.resolve("chat_attachments").deleteRecursively()
    }
    // ... existing onTrimMemory logic unchanged
}
```

### Ripple Effect
**2 files** modified. **~2 lines each.** Zero downstream effects.

### Testing Strategy
```
1. Send several messages with camera attachments
2. Start a new session → verify cacheDir/chat_attachments/ is empty
3. Send camera messages → background app fully → return → cacheDir/chat_attachments/ cleared (onTrimMemory path)
4. No crash when directory doesn't exist (deleteRecursively is safe on non-existent dirs)
```

### Definition of Done
- [ ] `createNewSession()` clears `cacheDir/chat_attachments/`
- [ ] `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` clears `cacheDir/chat_attachments/`
- [ ] No crash when directory is absent
- [ ] Manual test: camera usage → session end → directory empty

---

## AP-18 — CONVERSATIONSUMMARIZER ASYNC DISPATCH

### Current State
**Status:** Partially Active. Synchronous call in message recording coroutine causes 5–30 second UI freeze at 200-message boundary.

**Why Not Active:**
`MemoryManager.recordChatMessage()` calls `conversationSummarizer.summarize(oldMessages)` synchronously when message count exceeds 200. `ConversationSummarizer.summarize()` calls `LlamaManager.generate()` — a full LLM inference that takes 5–30 seconds. This blocks the entire message send path. The user cannot send the next message until summarization completes.

**Architecture note:** `MemoryManager` is a repository class — it has no `viewModelScope`. The correct scope is `CoroutineScope(SupervisorJob() + Dispatchers.Default)` created at application lifetime in `ServiceLocator`. This scope must be injected into `MemoryManager` via its constructor.

### Activation Path
```
ServiceLocator.kt — add applicationScope: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ↓
memory/repository/MemoryManager.kt — add applicationScope constructor parameter
    ↓
ServiceLocator.kt — pass applicationScope when constructing MemoryManager
    ↓
memory/repository/MemoryManager.kt — change synchronous summarizer call to applicationScope.launch { ... }
    ↓
Emit ActivityEvent.SUMMARIZING_HISTORY to AgentActivityBus before launch
    ↓
Emit ActivityEvent.SUMMARIZING_COMPLETE to AgentActivityBus after summarization
    ↓
ui/ChatScreen.kt — show "Compressing history..." chip on SUMMARIZING_HISTORY event
    ↓
Manual test: 201+ messages — no UI freeze; chip appears and disappears
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ServiceLocator.kt` | Add `val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`; pass to `MemoryManager` constructor |
| `memory/repository/MemoryManager.kt` | Add `applicationScope: CoroutineScope` constructor parameter; change summarizer call to `applicationScope.launch {}` |
| `ui/ChatScreen.kt` | Add chip/indicator for `ActivityEvent.SUMMARIZING_HISTORY` / `SUMMARIZING_COMPLETE` |

### Exact Changes

**`ServiceLocator.kt`** — add before `memoryManager` init:
```kotlin
val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
// Pass to MemoryManager:
val memoryManager = MemoryManager(
    applicationScope = applicationScope,
    // ... all existing parameters unchanged
)
```

**`MemoryManager.kt`** — change summarizer call:
```kotlin
class MemoryManager(
    private val applicationScope: CoroutineScope,  // ADD parameter
    // ... existing parameters unchanged
) {
    // ...

    suspend fun recordChatMessage(message: ChatMessage) {
        // ... existing recording logic ...
        if (messageCount > 200) {
            // BEFORE: conversationSummarizer.summarize(oldMessages)  ← BLOCKS
            // AFTER:
            applicationScope.launch {
                agentActivityBus.emit(ActivityEvent.SUMMARIZING_HISTORY)
                try {
                    conversationSummarizer.summarize(oldMessages)
                } finally {
                    agentActivityBus.emit(ActivityEvent.SUMMARIZING_COMPLETE)
                }
            }
            // Returns immediately; message send continues
        }
    }
}
```

**`ChatScreen.kt`** — add status chip:
```kotlin
val activityEvents by agentActivityBus.events.collectAsState(initial = null)
val isSummarizing = remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    agentActivityBus.events.collect { event ->
        when (event) {
            ActivityEvent.SUMMARIZING_HISTORY -> isSummarizing.value = true
            ActivityEvent.SUMMARIZING_COMPLETE -> isSummarizing.value = false
            else -> {}
        }
    }
}

AnimatedVisibility(isSummarizing.value) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Compressing history...", style = MaterialTheme.typography.labelMedium)
        }
    }
}
```

### Dependency Activation Graph
```
AP-18 Activated
    ↓
No UI freeze at 200-message boundary → send path always returns immediately
    ↓
applicationScope created in ServiceLocator
    ↓
Other repository classes that need app-lifetime coroutines can use this scope
    ↓
AP-22 (MemoryExtractor auto-wiring) can use the same applicationScope pattern
    ↓
User experience: no 30-second hang mid-conversation
```

### Ripple Effect
**3 files** modified. `MemoryManager` constructor signature changes — verify no other caller constructs `MemoryManager` directly outside `ServiceLocator` (grep confirms `ServiceLocator` is the only construction site).

### Testing Strategy
```
Manual tests:
1. Send 201+ messages → no UI freeze → message send returns immediately
2. "Compressing history..." chip appears → disappears after summarization

Unit tests:
3. MemoryManager.recordChatMessage() at count 201 → applicationScope.launch called → returns immediately (does not await)
4. Summarization failure in applicationScope → SUMMARIZING_COMPLETE still emitted (try/finally)
5. Multiple rapid messages after count 200 → no duplicate summarization launches (add guard flag)

Integration test:
6. After async summarization completes → old messages pruned; summary message stored in DB
7. Restart app after summarization → conversation context includes summary
```

### Rollback Strategy
Revert `MemoryManager.recordChatMessage()` change. The `applicationScope` in `ServiceLocator` is harmless to leave — it can stay.

### Definition of Done
- [ ] `ServiceLocator` creates and provides `applicationScope: CoroutineScope`
- [ ] `MemoryManager` constructor accepts `applicationScope` parameter
- [ ] `ConversationSummarizer.summarize()` called in `applicationScope.launch {}` (non-blocking)
- [ ] `SUMMARIZING_HISTORY` and `SUMMARIZING_COMPLETE` events emitted on `AgentActivityBus`
- [ ] "Compressing history..." chip appears/disappears in `ChatScreen`
- [ ] No UI freeze with 201+ messages (manual test on device)
- [ ] Summary stored correctly after async completion (integration test)

---

## AP-19 — N8N CONNECTOR FIRST-CLASS REGISTRATION

### Current State
**Status:** Partially Active. `N8nIntegration.kt` exists in `tools/` but is not a registered connector. Default URL is `http://localhost:5678/webhook/airi` — wrong for all production users. Not health-monitored. Not visible in `ConnectorsScreen`.

### Activation Path
```
CREATE connector/N8nConnector.kt — wraps N8nIntegration; configurable webhook URL from ConnectorAuthManager
    ↓
connector/ConnectorBootstrap.kt — register N8nConnector
    ↓
ui/ConnectorsScreen.kt — add webhook URL input field for N8n entry
    ↓
N8n connector now: visible in ConnectorsScreen, health-monitored, auth-managed
    ↓
Integration test: user enters webhook URL → N8n action invoked → HTTP POST reaches endpoint
    ↓
Production Ready
```

### Exact Files to Modify / Create
| File | Action |
|:---|:---|
| `connector/N8nConnector.kt` | CREATE — wraps `N8nIntegration`; uses `ConnectorAuthManager` for webhook URL |
| `connector/ConnectorBootstrap.kt` | Add N8n registration |
| `ui/ConnectorsScreen.kt` | Add webhook URL configuration UI for N8n |

### `N8nConnector.kt` Structure
```kotlin
class N8nConnector(
    private val connectorAuthManager: ConnectorAuthManager
) : Connector {
    override val id = "n8n"
    override val name = "N8n"
    override val type = ConnectorType.WEBHOOK

    private fun webhookUrl(): String? =
        connectorAuthManager.getCredential("n8n", "webhook_url")

    override suspend fun connect(): ConnectorResult {
        val url = webhookUrl()
        return if (url.isNullOrBlank()) {
            ConnectorResult.AuthRequired("Enter N8n webhook URL in Connectors settings")
        } else {
            // Attempt a test ping to verify connectivity
            try {
                val response = httpClient.get(url.replace("/webhook/airi", "/healthz"))
                if (response.isSuccessful) ConnectorResult.Success
                else ConnectorResult.Failure("N8n ping failed: ${response.code}")
            } catch (e: Exception) {
                ConnectorResult.Failure("N8n unreachable: ${e.message}")
            }
        }
    }

    override suspend fun execute(action: String, args: Map<String, Any>): ConnectorResult {
        val url = webhookUrl() ?: return ConnectorResult.AuthRequired("Configure N8n webhook URL first")
        return try {
            val payload = Json.encodeToString(mapOf("action" to action) + args)
            val response = httpClient.post(url) { body = payload; contentType = "application/json" }
            ConnectorResult.Success(response.body)
        } catch (e: Exception) {
            ConnectorResult.Failure("N8n request failed: ${e.message}")
        }
    }

    override suspend fun ping(): Boolean = webhookUrl()?.let { url ->
        try { httpClient.get(url).isSuccessful } catch (e: Exception) { false }
    } ?: false
}
```

### Dependency Activation Graph
```
AP-19 Activated
    ↓
N8n visible in ConnectorsScreen (health-monitored with 60s pings)
    ↓
Users can route automation workflows through N8n webhooks via agent commands
    ↓
All 15 connectors registered (13 original + GoogleConnector + N8nConnector)
```

### Ripple Effect
**3 files**: 1 created, 2 modified.

### Testing Strategy
```
1. User enters N8n webhook URL in ConnectorsScreen → stored in ConnectorAuthManager
2. Invoke N8n connector action → HTTP POST sent to webhook URL with action payload
3. No URL configured → ConnectorResult.AuthRequired (no crash)
4. URL configured but unreachable → ConnectorResult.Failure (not crash)
5. ConnectorHealthMonitor shows N8n in health dashboard
```

### Definition of Done
- [ ] `N8nConnector.kt` created and registered
- [ ] No localhost default — UNCONFIGURED state until URL set
- [ ] Webhook URL configurable in `ConnectorsScreen`
- [ ] Health-monitored with 60s pings
- [ ] Auth-managed via `ConnectorAuthManager`

---

## AP-20 — PREFERENCE COORDINATOR UNIFICATION

### Current State
**Status:** Partially Active. `PreferenceCoordinator` covers 3 of 5 preference stores. Theme and voice model prefs are excluded.

**Why Not Active:**
`PreferenceCoordinator` consolidates `airi_exec_prefs_secure`, `airi_ui_state`, `airi_language_settings`. But `airi_theme_prefs` (`ThemePreferences`) and `vosk_model_prefs` (`VoskModelManager.activeModelId`) are outside it. A "Reset to Defaults" operation misses theme and voice model settings. Multi-device sync cannot include unconsolidated prefs.

### Activation Path
```
domain/PreferenceCoordinator.kt — add getThemeMode() / setThemeMode() delegation to ThemePreferences
    ↓
domain/PreferenceCoordinator.kt — add getActiveVoiceModel() / setActiveVoiceModel() delegation to VoskModelManager
    ↓
domain/PreferenceCoordinator.kt — implement resetToDefaults() covering all 5 stores
    ↓
ui/GeneralSettingsScreen.kt — expose "Reset to Defaults" button → calls PreferenceCoordinator.resetToDefaults()
    ↓
sync/CloudSyncCoordinator.kt — include theme + voice prefs in sync payload
    ↓
Test: reset → theme reverts to SYSTEM; voice model clears
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `domain/PreferenceCoordinator.kt` | Add theme + voice model delegation; add `resetToDefaults()` |
| `ui/GeneralSettingsScreen.kt` | Expose "Reset to Defaults" action wired to `PreferenceCoordinator.resetToDefaults()` |
| `sync/CloudSyncCoordinator.kt` | Add theme mode and voice model to sync payload |

### Exact Methods to Add
```kotlin
// PreferenceCoordinator.kt — add:
fun getThemeMode(): ThemeMode = themePreferences.mode
fun setThemeMode(mode: ThemeMode) = themePreferences.setMode(mode)

fun getActiveVoiceModel(): String? = voskModelManager.activeModelId
fun setActiveVoiceModel(modelId: String?) = voskModelManager.setActiveModel(modelId)

fun resetToDefaults() {
    // Execution prefs
    execPrefs.reset()
    // UI state prefs
    uiStatePrefs.reset()
    // Language prefs
    languagePrefs.reset()
    // Theme prefs
    themePreferences.setMode(ThemeMode.SYSTEM)
    // Voice model prefs
    voskModelManager.setActiveModel(null)
}
```

### Dependency Activation Graph
```
AP-20 Activated
    ↓
"Reset to Defaults" now covers ALL 5 preference stores (not just 3)
    ↓
CloudSyncCoordinator sync now includes theme + voice model (required for A-49)
    ↓
A-49 (Voice Preferences Cloud Sync) can use unified sync payload
    ↓
GDPR "Reset" flow is now complete (all preferences reset as expected)
```

### Ripple Effect
**3 files** modified. The `ThemePreferences` and `VoskModelManager` classes are not changed — `PreferenceCoordinator` delegates to their existing APIs.

### Testing Strategy
```
Unit tests:
1. PreferenceCoordinator.resetToDefaults() → ThemePreferences.mode == ThemeMode.SYSTEM
2. PreferenceCoordinator.resetToDefaults() → VoskModelManager.activeModelId == null
3. PreferenceCoordinator.resetToDefaults() → all 3 existing pref stores also reset

Integration test:
4. GeneralSettingsScreen "Reset to Defaults" → all 5 stores return defaults
5. CloudSyncCoordinator sync payload includes theme mode and voice model ID
```

### Definition of Done
- [ ] `PreferenceCoordinator.getThemeMode()` / `setThemeMode()` delegate to `ThemePreferences`
- [ ] `PreferenceCoordinator.getActiveVoiceModel()` / `setActiveVoiceModel()` delegate to `VoskModelManager`
- [ ] `resetToDefaults()` covers all 5 stores
- [ ] `GeneralSettingsScreen` exposes "Reset to Defaults" wired to coordinator
- [ ] `CloudSyncCoordinator` sync payload includes theme + voice model

---

## AP-21 — WORLD RISK PROVIDER WIRING

### Current State
**Status:** Orphaned. `WorldRiskProvider` implements `RiskProvider` with real risk logic. Not instantiated. Never passed to `PermissionGovernanceLayer`.

**Why Not Active:**
`ServiceLocator` does not create a `WorldRiskProvider` instance. `PermissionGovernanceLayer` evaluates commands using regex only — no world state context (current app, recent agent actions, battery, location) is considered. A command that would be low-risk in isolation may be high-risk given world context (e.g., `send_message` when the user is at the lock screen).

### Activation Path
```
ServiceLocator.kt — instantiate WorldRiskProvider(worldStateManager)
    ↓
security/PermissionGovernanceLayer.kt — add worldRiskProvider constructor parameter
    ↓
ServiceLocator.kt — pass worldRiskProvider to PermissionGovernanceLayer
    ↓
security/PermissionGovernanceLayer.evaluate() — add worldRiskProvider.estimateRisk() call
    ↓
Unit test: high-risk world state → tool call blocked
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ServiceLocator.kt` | `val worldRiskProvider = WorldRiskProvider(worldStateManager)` |
| `ServiceLocator.kt` | Pass `worldRiskProvider` to `PermissionGovernanceLayer` constructor |
| `security/PermissionGovernanceLayer.kt` | Add `worldRiskProvider: WorldRiskProvider` constructor parameter; call `estimateRisk()` in `evaluate()` |

### Exact Change in `evaluate()`
```kotlin
fun evaluate(toolName: String, agentId: String, command: String): GovernanceDecision {
    // Existing: regex + encoding check (AP-09)
    val decoded = decodeAndExpand(command)
    if (dangerousPatterns.any { it.matches(decoded) }) {
        return GovernanceDecision.BLOCK("Dangerous pattern detected")
    }

    // ADD: world risk check
    val worldState = worldStateManager.snapshot()
    val worldRisk = worldRiskProvider.estimateRisk(toolName, worldState)
    if (worldRisk > WORLD_RISK_THRESHOLD) {
        return GovernanceDecision.BLOCK("World risk exceeded: $worldRisk for $toolName in current context")
    }

    return GovernanceDecision.ALLOW
}

companion object {
    private const val WORLD_RISK_THRESHOLD = 0.75f
}
```

### Dependency Activation Graph
```
AP-21 Activated
    ↓
PermissionGovernanceLayer is now context-aware (not just pattern-matching)
    ↓
Risk estimation considers: current app, device state, recent agent actions, location context
    ↓
FULL_AGENT mode is safer (world-aware risk evaluation)
    ↓
Security posture: +5 points
```

### Ripple Effect
**2 files** modified (ServiceLocator, PermissionGovernanceLayer). `WorldRiskProvider` and `WorldStateManager` are not changed — existing implementations are used as-is.

### Testing Strategy
```
Unit tests:
1. High-risk world state (device unlocked, sensitive app in foreground, recent aggressive commands)
   → evaluate("send_message", agentId, "...") → GovernanceDecision.BLOCK
2. Low-risk world state (idle, home screen, no recent activity)
   → evaluate("send_message", agentId, "...") → GovernanceDecision.ALLOW
3. WorldRiskProvider throws exception → evaluate() catches and defaults to ALLOW (fail-open for usability)
   [Note: consider fail-closed for security-critical operations]
```

### Definition of Done
- [ ] `WorldRiskProvider(worldStateManager)` instantiated in `ServiceLocator`
- [ ] `PermissionGovernanceLayer` receives `worldRiskProvider` via constructor
- [ ] `evaluate()` calls `worldRiskProvider.estimateRisk()` on every tool call
- [ ] High-risk world state test: tool call blocked

---

## AP-22 — MEMORY EXTRACTOR AUTO-WIRING

### Current State
**Status:** Orphaned. `MemoryExtractor.extract()` works. Never called in the primary message path.

**Why Not Active:**
`MemoryExtractor.extract(text)` performs heuristic fact extraction (name, location, preferences) and is fully implemented. It is not called in `ChatViewModel.sendMessage()` or `MemoryManager.recordChatMessage()`. User facts stated in chat ("My name is Ahmed, I live in Dubai") are never automatically stored as long-term memories. They are only stored if the user explicitly says "remember that…" and the `MemoryAgent` is invoked via the ACTION intent path.

### Activation Path
```
memory/repository/MemoryManager.kt — call MemoryExtractor.extract() in recordChatMessage() for assistant messages
    ↓
Filter facts by confidence > 0.8
    ↓
Call markAsMemory(fact.messageId) for high-confidence facts
    ↓
Integration test: tell AIRI your name → start new session → ask "what is my name?" → correct answer
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `memory/repository/MemoryManager.kt` | Add `MemoryExtractor.extract()` call in `recordChatMessage()` for assistant messages |

### Exact Change
```kotlin
// MemoryManager.recordChatMessage() — ADD after storing assistant message:
if (message.role == Role.ASSISTANT) {
    // Run extraction in the existing applicationScope (from AP-18)
    applicationScope.launch {
        val facts = memoryExtractor.extract(message.content)
        facts.filter { it.confidence > 0.8f }.forEach { fact ->
            markAsMemory(fact.messageId ?: message.id)
            // Optional: emit a debug event for observability
            agentActivityBus.emit(ActivityEvent.MEMORY_FACT_EXTRACTED(fact.text))
        }
    }
}
```

**Note:** `MemoryExtractor` must be injected into `MemoryManager` via constructor if not already present. Verify `MemoryManager` constructor in `ServiceLocator` and add `memoryExtractor` parameter if needed.

### Dependency Activation Graph
```
AP-22 Activated
    ↓
User facts are now auto-extracted from every assistant message
    ↓
RagRetriever can retrieve stored facts in future sessions
    ↓
ChatViewModel.sendMessage() → context includes previously extracted user facts
    ↓
Long-term memory system (Feature 07) is now fully active end-to-end
    ↓
User experience: AIRI "remembers" facts stated in conversation without explicit "remember" command
```

### Ripple Effect
**1 file** modified (`MemoryManager.kt`). If `MemoryExtractor` is not yet a `MemoryManager` constructor parameter, `ServiceLocator.kt` also needs updating (+1 file).

### Testing Strategy
```
Integration test (key test for this feature):
1. New conversation → tell AIRI: "My name is Ahmed, I live in Dubai"
2. Receive assistant response (any response to acknowledge)
3. Start a NEW conversation session
4. Ask: "What is my name?" → AIRI answers "Ahmed" (from extracted memory)
5. Ask: "Where do I live?" → AIRI answers "Dubai"

Unit tests:
6. MemoryExtractor.extract("My name is Ahmed") → returns fact with confidence > 0.8, text = "name: Ahmed"
7. MemoryExtractor.extract("random message with no facts") → returns empty list or low-confidence facts
8. recordChatMessage(assistantMessage with facts) → markAsMemory() called for high-confidence facts
9. recordChatMessage(userMessage) → no extraction (user messages not extracted)

Performance:
10. extract() runs in applicationScope (non-blocking) → recordChatMessage() returns immediately
```

### Definition of Done
- [ ] `MemoryExtractor.extract()` called for every assistant message in `recordChatMessage()`
- [ ] Facts with confidence > 0.8 marked as long-term memories via `markAsMemory()`
- [ ] Extraction runs in `applicationScope` (non-blocking)
- [ ] Integration test: name stated in session → retrievable in new session

---

## AP-23 — CHAT EXPORTER / IMPORTER UI EXPOSURE

### Current State
**Status:** Backend Only. `ChatExporter` and `ChatImporter` are complete SAF-based implementations. Zero UI entry points.

**Why Not Active:**
Neither class is called from any UI. Users cannot export or import chat history despite the implementation being complete and ready.

### Activation Path
```
ui/PrivacyDataSettingsScreen.kt — add "Export Chat History" action → ChatExporter.export()
    ↓
ui/PrivacyDataSettingsScreen.kt — add "Import Chat History" action → ChatImporter.import()
    ↓
Wire ViewModel methods: ChatViewModel or PrivacyDataSettingsViewModel.exportHistory() / importHistory()
    ↓
Manual test: export → verify JSON valid → import → verify messages appear
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/PrivacyDataSettingsScreen.kt` | Add "Export Chat History" and "Import Chat History" action items (~20 lines) |
| `ui/viewmodel/PrivacyDataSettingsViewModel.kt` (or ChatViewModel) | Add `exportHistory(sessionId)` and `importHistory()` methods |

### Exact UI Changes
```kotlin
// PrivacyDataSettingsScreen.kt — add after existing data management items:
PrivacyActionItem(
    title = "Export Chat History",
    subtitle = "Save all conversations as JSON file",
    icon = Icons.Default.FileDownload,
    onClick = { privacyViewModel.exportHistory() }
)

PrivacyActionItem(
    title = "Import Chat History",
    subtitle = "Restore conversations from a JSON export",
    icon = Icons.Default.FileUpload,
    onClick = { privacyViewModel.importHistory() }
)

// ViewModel methods:
fun exportHistory() {
    viewModelScope.launch {
        val uri = chatExporter.export(sessionId = null /* all sessions */)
        _uiState.update { it.copy(exportSuccess = uri) }
    }
}

fun importHistory() {
    viewModelScope.launch {
        val result = chatImporter.import()
        _uiState.update { it.copy(importResult = result) }
    }
}
```

### Testing Strategy
```
1. PrivacyDataSettingsScreen → "Export Chat History" → SAF file picker → JSON file written
2. Open exported JSON → valid format with all messages
3. "Import Chat History" → select exported JSON file → messages appear in conversation history
4. Import of invalid/corrupt JSON → error message shown (no crash)
```

### Definition of Done
- [ ] "Export Chat History" button in `PrivacyDataSettingsScreen` → calls `ChatExporter.export()`
- [ ] "Import Chat History" button → calls `ChatImporter.import()`
- [ ] Export → valid JSON file with all messages
- [ ] Import → messages restored correctly

---

## AP-24 — TEMPLATES SCREEN NAVIGATION

### Current State
**Status:** Disconnected. `AiriRoute.TEMPLATES` registered. `TemplatesScreen.kt` compiled. Zero navigate() callers.

### Activation Path
```
ui/ChatScreen.kt — add TEMPLATES entry to the plus-menu or toolbar menu
    ↓
onNavigate(AiriRoute.TEMPLATES) on tap
    ↓
Manual test: tap → TemplatesScreen opens
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/ChatScreen.kt` | Add Templates to plus-menu or toolbar overflow menu (~5 lines) |

### Exact Change
```kotlin
// In ChatScreen's plus-menu DropdownMenu or IconButton row:
DropdownMenuItem(
    text = { Text("Templates") },
    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
    onClick = {
        menuExpanded = false
        onNavigate(AiriRoute.TEMPLATES)
    }
)
```

### Definition of Done
- [ ] `AiriRoute.TEMPLATES` has ≥ 1 `navigate()` caller
- [ ] Tap "Templates" in ChatScreen menu → `TemplatesScreen` opens

---

## AP-25 — SETTINGS ABOUT / APP INFO NAVIGATION

### Current State
**Status:** Disconnected. Both routes registered. Both screens compiled. Zero callers.

### Activation Path
```
ui/SettingsScreen.kt — add "About AIRI" as bottom entry → AiriRoute.SETTINGS_ABOUT
    ↓
ui/AboutScreen.kt — add "Technical Details" link → AiriRoute.APP_INFO
    ↓
Manual test: Settings → About → AppInfo navigation chain works
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/SettingsScreen.kt` | Add "About AIRI" `SettingsNavItem` at bottom (~5 lines) |
| `ui/AboutScreen.kt` | Add "Technical Details" text link → `onNavigate(AiriRoute.APP_INFO)` (~3 lines) |

### Exact Changes
```kotlin
// SettingsScreen.kt — at bottom of settings list:
SettingsNavItem(
    title = "About AIRI",
    subtitle = "Version, licenses, acknowledgements",
    icon = Icons.Default.Info,
    onClick = { onNavigate(AiriRoute.SETTINGS_ABOUT) }
)

// AboutScreen.kt — add technical details link:
TextButton(onClick = { onNavigate(AiriRoute.APP_INFO) }) {
    Text("Technical Details")
}
```

### Definition of Done
- [ ] `AiriRoute.SETTINGS_ABOUT` has ≥ 1 navigate() caller
- [ ] `AiriRoute.APP_INFO` has ≥ 1 navigate() caller
- [ ] Settings → About → Technical Details: 3-screen chain navigates correctly

---

## AP-C01 — DYNAMIC INPUT BAR

### Current State
**Status:** Partially Active. `AdvancedInputBar` exists with static height regardless of context.

**Why Not Active:**
No `inputBarMode` StateFlow in `ChatViewModel`. `AdvancedInputBar` does not observe agent state or text length to adapt its height. Height, visible buttons, and layout are static at all times — during agent execution, when text is empty, when text is long, and when voice mode is active.

### Activation Path
```
ui/viewmodel/ChatViewModel.kt — add InputBarMode sealed class + inputBarMode: StateFlow
    ↓
ui/ChatScreen.kt → AdvancedInputBar: collect inputBarMode; animate height via animateDpAsState()
    ↓
AdvancedInputBar: show secondary buttons only in Standard/Expanded
    ↓
AdvancedInputBar: show only Cancel button in AgentActive mode
    ↓
Manual test: idle (Compact) → typing (Standard/Expanded) → agent running (AgentActive) → transitions smooth
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/ChatViewModel.kt` | Add `InputBarMode` sealed class; add `inputBarMode: StateFlow<InputBarMode>` |
| `ui/components/AdvancedInputBar.kt` | Collect `inputBarMode`; animate height; show/hide secondary buttons |

### Exact ChatViewModel Addition
```kotlin
sealed class InputBarMode {
    object Compact     : InputBarMode()  // idle, no text
    object Standard    : InputBarMode()  // default
    object Expanded    : InputBarMode()  // text > 3 lines
    object AgentActive : InputBarMode()  // executing
}

val inputBarMode: StateFlow<InputBarMode> = combine(
    agentState, inputText, voiceModeActive
) { state, text, voice ->
    when {
        state == AgentState.EXECUTING  -> InputBarMode.AgentActive
        text.lines().size > 3          -> InputBarMode.Expanded
        text.isEmpty() && !voice       -> InputBarMode.Compact
        else                           -> InputBarMode.Standard
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, InputBarMode.Standard)
```

### Exact AdvancedInputBar Addition
```kotlin
@Composable
fun AdvancedInputBar(
    chatViewModel: ChatViewModel,
    // ... existing params
) {
    val mode by chatViewModel.inputBarMode.collectAsState()
    val targetHeight = when (mode) {
        InputBarMode.Compact     -> 48.dp
        InputBarMode.Standard    -> 64.dp
        InputBarMode.Expanded    -> 120.dp
        InputBarMode.AgentActive -> 36.dp
    }
    val height by animateDpAsState(targetHeight, animationSpec = spring(stiffness = Spring.StiffnessMedium))

    val showSecondaryButtons = mode == InputBarMode.Standard || mode == InputBarMode.Expanded

    Box(modifier = Modifier.height(height)) {
        // existing input row
        if (showSecondaryButtons) {
            // plan mode toggle, skill launcher, etc.
        }
        if (mode == InputBarMode.AgentActive) {
            Button(onClick = { chatViewModel.cancelAgent() }) { Text("Cancel") }
        }
    }
}
```

### Ripple Effect
**2 files** modified. No new files. The `combine()` StateFlow is additive.

### Testing Strategy
```
1. Idle, no text → height = 48dp; secondary buttons hidden
2. Type text → height = 64dp; secondary buttons visible
3. Type > 3 lines → height = 120dp
4. Tap Send → agent executing → height = 36dp; only Cancel visible
5. Agent completes → height returns to 48dp
6. Height transitions are smooth (animateDpAsState, Spring.StiffnessMedium)
7. No Compose jank (< 16ms frame time during transitions)
```

### Definition of Done
- [ ] `ChatViewModel.inputBarMode: StateFlow<InputBarMode>` present and correct
- [ ] `AdvancedInputBar` height animates based on mode (48 / 64 / 120 / 36 dp)
- [ ] Secondary buttons hidden in Compact and AgentActive modes
- [ ] Cancel-only in AgentActive mode
- [ ] No Compose jank during transitions (verify with FrameTimingMonitor)

---

## AP-C02 — INLINE ATTACHMENT PREVIEWS

### Current State
**Status:** Architecture Only. `VisionImage.downscale()` is active but no thumbnail is shown in the input bar before sending.

**Why Not Active:**
No `stagedAttachments: StateFlow<List<ChatAttachment>>` in `ChatViewModel`. `AdvancedInputBar` has no preview row. Users cannot see what they are about to send or remove individual attachments.

### Activation Path
```
ui/viewmodel/ChatViewModel.kt — add stagedAttachments: MutableStateFlow<List<ChatAttachment>>
    ↓
ChatViewModel — add removeAttachment(); clear stagedAttachments on send
    ↓
ui/components/AdvancedInputBar.kt — add LazyRow of AttachmentThumbnail composables
    ↓
AttachmentThumbnail — image: 56dp AsyncImage + "×"; document: file icon + name; video: first frame + play icon
    ↓
Manual test: attach image → thumbnail shown → tap × → removed → send → thumbnail cleared
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/ChatViewModel.kt` | Add `stagedAttachments`, `removeAttachment()`, clear on send |
| `ui/components/AdvancedInputBar.kt` | Add `LazyRow` of `AttachmentThumbnail` when `stagedAttachments` non-empty |

### Exact ChatViewModel Additions
```kotlin
val stagedAttachments: MutableStateFlow<List<ChatAttachment>> = MutableStateFlow(emptyList())

fun stageAttachment(attachment: ChatAttachment) {
    stagedAttachments.update { it + attachment }
}

fun removeAttachment(attachment: ChatAttachment) {
    stagedAttachments.update { it - attachment }
}

// In sendMessage():
// After dispatching to agent: stagedAttachments.value = emptyList()
```

### Exact AdvancedInputBar Addition
```kotlin
val attachments by chatViewModel.stagedAttachments.collectAsState()
if (attachments.isNotEmpty()) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(attachments) { attachment ->
            AttachmentThumbnail(
                attachment = attachment,
                onRemove = { chatViewModel.removeAttachment(attachment) }
            )
        }
    }
}
```

### Definition of Done
- [ ] `stagedAttachments: StateFlow<List<ChatAttachment>>` in `ChatViewModel`
- [ ] Thumbnail shown for each staged attachment (image/document/video)
- [ ] "×" removes attachment from staging list
- [ ] `stagedAttachments` cleared after `sendMessage()`
- [ ] No thumbnail shown after send (staging cleared)

---

## AP-C03 — REMOVE / RELOCATE ACTIVITY PANEL

### Current State
**Status:** Legacy still used. `AgentPlanOverlay` blocks chat during agent execution.

**Why Not Active (for relocation):**
`AgentPlanOverlay` is rendered inline over the chat message list, blocking the conversation during execution. On phone-sized screens this prevents the user from reading the conversation context while the agent works. The correct UX is a `ModalBottomSheet` on phones and a persistent sidebar on tablets (sw600dp).

### Activation Path
```
ui/ChatScreen.kt — replace AgentPlanOverlay inline position with ModalBottomSheet
    ↓
ChatScreen — add compact AgentStatusChip in input bar area (always visible during execution)
    ↓
AgentPlanViewModel.isVisible — auto-collapse when agentState == IDLE
    ↓
Responsive: phone → ModalBottomSheet; tablet (sw600dp) → persistent sidebar
    ↓
AP-C04 (conditional panel display) can now layer on top of this
    ↓
Manual test: send message → agent runs → BottomSheet appears (not blocking) → chat readable
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/ChatScreen.kt` | Replace `AgentPlanOverlay` placement with `ModalBottomSheet`; add `AgentStatusChip` |
| `ui/viewmodel/AgentPlanViewModel.kt` | Auto-collapse when `agentState == IDLE` |

### Exact ChatScreen Change
```kotlin
// REMOVE: AgentPlanOverlay inline rendering

// ADD:
val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
val isPanelVisible by agentPlanViewModel.isVisible.collectAsState()

if (isPanelVisible) {
    ModalBottomSheet(
        onDismissRequest = { agentPlanViewModel.collapse() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AgentPlanContent(
            viewModel = agentPlanViewModel,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ADD compact status chip in input bar area:
val agentState by chatViewModel.agentState.collectAsState()
AnimatedVisibility(agentState != AgentState.IDLE) {
    AgentStatusChip(
        state = agentState,
        onClick = { agentPlanViewModel.expand() },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
```

### Dependency Activation Graph
```
AP-C03 Activated
    ↓
Chat messages readable during agent execution (non-blocking panel)
    ↓
AP-C04 (conditional panel display) layers on top → panel only expands for 3+ steps
    ↓
AP-C06 (live execution state) — panel content now shows live step status
    ↓
UX completeness: +10 points toward 90+ target
```

### Testing Strategy
```
1. Send message → agent executes → chat list is NOT obscured (BottomSheet, not overlay)
2. ModalBottomSheet swipeable down → collapses
3. AgentStatusChip shows current state ("🔄 Searching..." / "✅ Done")
4. Tap AgentStatusChip → BottomSheet expands
5. Agent completes (IDLE) → BottomSheet auto-collapses
6. Tablet (sw600dp): panel appears as sidebar, not BottomSheet
```

### Definition of Done
- [ ] `AgentPlanOverlay` removed from inline position in `ChatScreen`
- [ ] `ModalBottomSheet` with `AgentPlanContent` on phones
- [ ] Persistent sidebar on tablet (sw600dp)
- [ ] `AgentStatusChip` always visible during execution
- [ ] Auto-collapse on `agentState == IDLE`
- [ ] Chat message list fully readable during agent execution

---

## AP-C04 — PLANNING PANEL CONDITIONAL DISPLAY

### Current State
**Status:** Partially Active. Panel appears for all executions including trivial 1-step tasks.

**Depends on:** AP-C03 (ModalBottomSheet must be in place first).

### Activation Path
```
ui/viewmodel/AgentPlanViewModel.kt — add showPanel: StateFlow<Boolean> = steps.size >= 3
    ↓
ui/ChatScreen.kt — show ModalBottomSheet only when showPanel = true OR isPlanMode = true
    ↓
1–2 steps: compact AgentStatusChip only
    ↓
3+ steps: ModalBottomSheet auto-expands
    ↓
Manual plan mode: always show panel
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/AgentPlanViewModel.kt` | Add `showPanel: StateFlow<Boolean>` |
| `ui/ChatScreen.kt` | Condition ModalBottomSheet expansion on `showPanel || isPlanMode` |

### Exact Changes
```kotlin
// AgentPlanViewModel.kt:
val showPanel: StateFlow<Boolean> = _steps
    .map { steps -> steps.size >= 3 }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

// ChatScreen.kt — modify ModalBottomSheet condition:
val showPanel by agentPlanViewModel.showPanel.collectAsState()
val isPlanMode by chatViewModel.isPlanModeActive.collectAsState()

// Only show ModalBottomSheet when warranted:
if (isPanelVisible && (showPanel || isPlanMode)) {
    ModalBottomSheet(...) { AgentPlanContent(...) }
}
// When isPanelVisible but !showPanel && !isPlanMode: only AgentStatusChip shows
```

### Definition of Done
- [ ] 1–2 step executions: no BottomSheet; only AgentStatusChip
- [ ] 3+ step executions: BottomSheet auto-expands
- [ ] Plan mode active: BottomSheet always shown regardless of step count

---

## AP-C05 — DAILY CREDITS COUNTER ACTIVATION

### Current State
**Status:** Partially Active. `todayTokens: StateFlow<Long>` exists in `ChatViewModel`. Display in `ChatScreen` not confirmed visible.

### Activation Path
```
Verify ChatViewModel exposes: todayTokens: StateFlow<Long> and dailyTokenLimit: StateFlow<Long>
    ↓
If absent: add todayTokens from TokenAccountant.totalTokensToday()
           add dailyTokenLimit from SubscriptionManager.getDailyLimit()
    ↓
ui/ChatScreen.kt — add CreditsIndicator in header or below message list
    ↓
Color logic: < 80% → neutral; 80–100% → amber; > 100% → red + "Upgrade" CTA
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/ChatViewModel.kt` | Verify/add `todayTokens` and `dailyTokenLimit` StateFlows |
| `ui/ChatScreen.kt` | Add `CreditsIndicator` composable in header area |

### Exact ChatScreen Addition
```kotlin
val todayTokens by chatViewModel.todayTokens.collectAsState()
val dailyTokenLimit by chatViewModel.dailyTokenLimit.collectAsState()

if (dailyTokenLimit > 0) {
    val usageRatio = todayTokens.toFloat() / dailyTokenLimit
    val indicatorColor = when {
        usageRatio < 0.8f -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        usageRatio < 1.0f -> Color(0xFFFFA000)  // amber
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = "${todayTokens.toFormattedK()} / ${dailyTokenLimit.toFormattedK()} tokens",
            style = MaterialTheme.typography.labelSmall,
            color = indicatorColor
        )
        if (usageRatio >= 1.0f) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onNavigate(AiriRoute.SUBSCRIPTION) }) {
                Text("Upgrade", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
```

### Definition of Done
- [ ] `todayTokens: StateFlow<Long>` and `dailyTokenLimit: StateFlow<Long>` in `ChatViewModel`
- [ ] Credits counter visible in `ChatScreen`
- [ ] Color changes: neutral → amber → red at correct thresholds
- [ ] "Upgrade for more" CTA shown when limit reached

---

## AP-C06 — PLANNER CONNECTED TO LIVE EXECUTION ENGINE

### Current State
**Status:** Partially Active. `AgentPlanViewModel.steps` driven by `ExecutionStatusBus` but live step-level timing and tool call sub-items are absent.

**Depends on:** AP-C03, AP-C04.

### Activation Path
```
ui/viewmodel/AgentPlanViewModel.kt — subscribe to ExecutionStatusBus.StepUpdate events
    ↓
Update step status (PENDING/RUNNING/DONE/FAILED), startedAt, completedAt
    ↓
ui/components/AgentPlanContent.kt — render status icon + elapsed time for RUNNING steps
    ↓
Render active tool calls as sub-items within a step row
    ↓
Manual test: run multi-step agent → watch steps update live
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/AgentPlanViewModel.kt` | Subscribe to `ExecutionStatusBus.StepUpdate` events; update step states |
| `ui/components/AgentPlanContent.kt` | Render step status icons, elapsed time, tool call sub-items |

### Exact ViewModel Change
```kotlin
// AgentPlanViewModel init block — add:
executionStatusBus.events
    .filterIsInstance<ExecutionEvent.StepUpdate>()
    .onEach { event ->
        _steps.update { current ->
            current.map { step ->
                if (step.id == event.stepId) step.copy(
                    status = event.status,
                    startedAt = event.startedAt ?: step.startedAt,
                    completedAt = event.completedAt ?: step.completedAt,
                    activeToolCall = event.activeToolCall
                ) else step
            }
        }
    }
    .launchIn(viewModelScope)
```

### Exact Plan Content Rendering
```kotlin
// AgentPlanContent.kt — for each PlanStep:
PlanStepRow(step = step) {
    // Status icon:
    when (step.status) {
        StepStatus.PENDING  -> Icon(Icons.Default.Schedule, tint = Color.Gray)
        StepStatus.RUNNING  -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
        StepStatus.DONE     -> Icon(Icons.Default.CheckCircle, tint = Color.Green)
        StepStatus.FAILED   -> Icon(Icons.Default.Error, tint = Color.Red)
    }
    // Elapsed time for RUNNING:
    if (step.status == StepStatus.RUNNING && step.startedAt != null) {
        val elapsed = (System.currentTimeMillis() - step.startedAt) / 1000
        Text("${elapsed}s", style = MaterialTheme.typography.labelSmall)
    }
    // Tool call sub-item:
    step.activeToolCall?.let { tool ->
        Row(modifier = Modifier.padding(start = 24.dp)) {
            Icon(Icons.Default.Build, modifier = Modifier.size(12.dp))
            Text(tool.name, style = MaterialTheme.typography.labelSmall)
        }
    }
}
```

### Definition of Done
- [ ] Each plan step shows: PENDING / RUNNING (+ spinner + elapsed time) / DONE / FAILED
- [ ] Active tool calls shown as sub-items within RUNNING step
- [ ] Status updates are live — step state updates within 1s of actual step state change
- [ ] No race conditions: rapid step updates handled correctly by `_steps.update {}`

---

## AP-C07 — THINKING ANIMATION

### Current State
**Status:** Architecture Only. No pre-first-token indicator. App appears frozen during inference.

**Why Not Active:**
`agentState: StateFlow<AgentState>` and `streamingText: StateFlow<String>` both exist in `ChatViewModel`. No composable checks `agentState == THINKING && streamingText.isEmpty()` to show an animation. Users see a frozen UI between message send and first streaming token — a gap that can be 2–15 seconds for local LLM inference.

### Activation Path
```
Create ThinkingAnimation composable (3 dots, staggered bounce, 400ms cycle)
    ↓
ui/ChatScreen.kt — add condition: agentState == THINKING && streamingText.isEmpty()
    ↓
Show ChatBubble(role = ASSISTANT) { ThinkingAnimation() } when condition is true
    ↓
Dismiss when streamingText.isNotEmpty() (first token arrives)
    ↓
Manual test: send message → 3-dot animation appears → first token replaces it
    ↓
Production Ready
```

### Exact Files to Modify / Create
| File | Action |
|:---|:---|
| `ui/components/ThinkingAnimation.kt` | CREATE — 3-dot staggered bounce composable |
| `ui/ChatScreen.kt` | Add condition + `ThinkingAnimation` in message list |

### ThinkingAnimation Implementation
```kotlin
@Composable
fun ThinkingAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..2).forEach { i ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = i * 133),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$i"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .offset(y = offsetY.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
```

### Exact ChatScreen Addition
```kotlin
// In message list LazyColumn, after existing message items:
val agentState by chatViewModel.agentState.collectAsState()
val streamingText by chatViewModel.streamingText.collectAsState()

if (agentState == AgentState.THINKING && streamingText.isEmpty()) {
    item(key = "thinking_animation") {
        ChatBubble(role = Role.ASSISTANT) {
            ThinkingAnimation(modifier = Modifier.padding(8.dp))
        }
    }
}
```

### Testing Strategy
```
1. Send message with local LLM → 3-dot animation appears immediately
2. First streaming token arrives → animation disappears; streaming text appears
3. Animation frame time < 16ms (no jank) — verify with FrameTimingMonitor
4. Animation does NOT appear when agentState == IDLE
5. Animation does NOT appear when streamingText is non-empty
```

### Definition of Done
- [ ] `ThinkingAnimation.kt` created with 3-dot staggered bounce
- [ ] Animation appears when `agentState == THINKING && streamingText.isEmpty()`
- [ ] Animation dismissed on first streaming token
- [ ] No Compose jank (< 16ms frame time per FrameTimingMonitor)

---

## AP-C08 — REMOVE INTRUSIVE CONTEXT RESET SNACKBARS

### Current State
**Status:** Legacy still used. "Context was reset" snackbar interrupts conversation mid-flow.

**Why Not Active (for removal):**
When KV cache overflows (common with local LLM on long conversations), `ChatScreen` shows a `Snackbar` with messages like "Context was reset" or "Context overflow." These are implementation details that confuse users and interrupt the conversation flow without providing any actionable information.

### Activation Path
```
ui/ChatScreen.kt — locate all snackbarHostState.showSnackbar() calls
    ↓
Identify: "Context was reset", "Context overflow", any KV-cache-related messages
    ↓
For each: remove snackbar emission; replace with auditRepository.log("CONTEXT_RESET", reason)
    ↓
Keep: genuine error snackbars (credit exhaustion, server error, permission denied)
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/ChatScreen.kt` | Remove context-reset snackbar calls; replace with audit log |
| `ui/viewmodel/ChatViewModel.kt` | Remove or convert context-reset `showSnackbar()` emissions |

### Snackbars to REMOVE (replace with audit log):
- "Context was reset"
- "Context overflow"
- "KV cache reset"
- "Memory compressed"
- Any variant of the above

### Snackbars to KEEP:
- "Failed to send message — server error"
- "Daily credit limit reached"
- "Microphone permission required"
- "Network unavailable"
- Any user-action-failure message

### Exact Replacement Pattern
```kotlin
// BEFORE:
snackbarHostState.showSnackbar("Context was reset")

// AFTER:
ServiceLocator.auditRepository.logSync("CONTEXT_RESET", "KV cache overflow — context window compressed")
// Optional (debug mode only):
if (BuildConfig.DEBUG || agentViewModel.debugMode.value) {
    snackbarHostState.showSnackbar("Context compressed (debug)")
}
```

### Definition of Done
- [ ] Zero "Context was reset" snackbars in production builds
- [ ] Context reset events logged to `AuditRepository` with tag `CONTEXT_RESET`
- [ ] All genuine error snackbars retained
- [ ] Debug mode may show subtle indicator; production shows nothing

---

## AP-C09 — LARGE PROMPT → CONVERT TO FILE WORKFLOW

### Current State
**Status:** Architecture Only. `PromptBudgetLedger.estimateTokens()` exists. No large-input detection in `AdvancedInputBar`.

**Why Not Active:**
Very long text pastes (>2000 characters) sent as raw message text consume large context windows and may fail for local LLMs with small context budgets. `ChatExporter` and `DocumentProcessorAgent` exist to handle file-based text. No detection or guidance exists in the input bar.

### Activation Path
```
ui/components/AdvancedInputBar.kt — add showConvertBanner condition (inputText.length > 2000)
    ↓
AnimatedVisibility: non-blocking banner with "Convert" and "Keep as text" actions
    ↓
ChatViewModel.saveInputAsFile(text) — write to cacheDir; return Uri
    ↓
ChatViewModel.stageAttachment(DocumentAttachment(uri)) — attach the file
    ↓
ChatViewModel.clearInputText() — clear text field after conversion
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/components/AdvancedInputBar.kt` | Add `showConvertBanner`; add `AnimatedVisibility` banner |
| `ui/viewmodel/ChatViewModel.kt` | Add `saveInputAsFile(text: String): Uri` |

### Exact Implementation
```kotlin
// AdvancedInputBar.kt:
val inputText by chatViewModel.inputText.collectAsState()
val showConvertBanner = inputText.length > 2000

AnimatedVisibility(showConvertBanner) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Long text detected. Convert to file for better processing?",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium
        )
        TextButton(onClick = {
            val uri = chatViewModel.saveInputAsFile(inputText)
            chatViewModel.clearInputText()
            chatViewModel.stageAttachment(DocumentAttachment(uri))
        }) { Text("Convert") }
        TextButton(onClick = { /* user dismisses banner */ }) { Text("Keep") }
    }
}

// ChatViewModel.kt:
fun saveInputAsFile(text: String): Uri {
    val file = context.cacheDir.resolve("input_${System.currentTimeMillis()}.txt")
    file.writeText(text)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}
```

### Definition of Done
- [ ] Banner appears when input text exceeds 2000 characters
- [ ] "Convert" → text saved as file attachment; input field cleared
- [ ] "Keep as text" → banner dismisses; text remains in input
- [ ] Converted file staged as `DocumentAttachment` with thumbnail (from AP-C02)

---

*AIRI Activation Plan — Part 2 complete.*
*Items covered: AP-12 through AP-25, AP-C01 through AP-C09 (Wave 2 + Wave 3 full)*
*Security baseline (Part 1) must complete before AP-19 (N8n connector), AP-20 (PreferenceCoordinator), AP-22 (MemoryExtractor).*
