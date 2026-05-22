# AIRI Lifecycle Audit — Phase H5
*Static analysis. Runtime verification requires physical device.*

---

## What Is Correct

### ViewModel Lifecycle
- `ChatViewModel` extends `AndroidViewModel` — survives configuration changes ✓
- All coroutines use `viewModelScope` — auto-cancelled on ViewModel `onCleared()` ✓
- No `Activity` reference stored in `ChatViewModel` (uses `appContext` only) ✓
- Voice transcript bus collector runs in `viewModelScope` — not duplicated on rotation ✓

### WorkManager Persistence
- `AgentWorker` (2h periodic) survives process death — WorkManager re-enqueues ✓
- `ScheduledJobOrchestrator` jobs persist in SharedPreferences — survive process death ✓
- `ScheduledAgentWorker` (new) uses `CoroutineWorker` — lifecycle-safe ✓

### Service Lifecycle
- `LiveVoiceService` is a foreground service — survives app backgrounding ✓
- `AiriAccessibilityService` has proper `onServiceConnected`/`onInterrupt` ✓

### RuntimeHealthMonitor + ExecutionWatchdog
- Both use `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — independent of Activity ✓
- No Activity leaks possible from these singletons ✓

---

## Confirmed Gaps

### L1: ChatViewModel BroadcastReceiver not unregistered (POTENTIAL LEAK)
**Severity: MEDIUM**
In `ChatViewModel.init`:
```kotlin
appContext.registerReceiver(downloadCompleteReceiver, filter)
```
A `BroadcastReceiver` registered on `appContext` (ApplicationContext) persists indefinitely.
It is unregistered in `override fun onCleared()` — but only if `onCleared()` is called.
If the app process is killed without `onCleared()` (e.g. SIGKILL), the receiver leaks until process restart.
Since it's registered on `appContext` not `Activity`, it cannot leak the Activity.
The leak duration is bounded by process lifetime. **Low real-world risk.**

### L2: Navigation back stack not restored after process death
**Severity: MEDIUM**
If the app process is killed while the user is on `ConnectorsScreen`, on re-launch Android
restores the NavBackStack via `rememberNavController()` + `SavedStateHandle`. However,
`ChatViewModel` re-initializes fresh — the streaming state, model state, and agent state
all reset. The user sees a blank chat screen with no indication of the interrupted session.
The last chat session is persisted via Room DB and can be restored — but this is not
triggered automatically on process recreation. A `SavedStateHandle`-based restoration
call is missing.

### L3: Voice session not recovered after audio interruption
**Severity: MEDIUM**
If a phone call arrives during a duplex voice session:
1. `AudioManager` sends `AUDIOFOCUS_LOSS`
2. `LiveVoiceService.onAudioFocusChange()` handles `AUDIOFOCUS_LOSS` → stops recording
3. After the call ends, `AUDIOFOCUS_GAIN` is received
4. **GAP**: There is no logic to resume the voice session after regaining focus.
The session stays paused indefinitely until the user manually re-taps the mic.

### L4: `ExecutionWatchdog.autoCancelStuck = false`
**Severity: LOW**
Stuck plans are detected and logged but never auto-cancelled. A plan that hangs
for > 5 minutes (the stuck threshold) will hold its `Mutex` lock inside
`ProductionAgentOrchestrator` indefinitely, blocking all subsequent plan execution.
This is a conservative safety choice but means stuck plans require manual intervention
(killing the app) in production.

### L5: ChatViewModel memory pressure subscription replay risk
**Severity: LOW**
`EventBus._events` has `replay = 50`. The new `observeMemoryPressureBus()` collector
processes up to 50 replayed events on ViewModel init. A CRITICAL pressure event
from a previous session would trigger `llamaManager.unloadModel()` on the fresh init —
before any model has been loaded. `unloadModel()` on a not-loaded manager is safe
(`isLoaded = false` returns early), but this is worth noting.

---

## Process Death Recovery — Current State

| State | Persisted | Restored on relaunch |
|---|---|---|
| Chat history (messages) | Room DB ✓ | Manual `loadInitialSession()` ✓ |
| Selected model | `ModelRegistry` SharedPreferences ✓ | `loadModel(savedModel)` in init ✓ |
| Scheduled jobs | SharedPreferences ✓ | `orchestrator.listJobs()` ✓ |
| Theme mode | `ThemePreferences` SharedPreferences ✓ | `collectAsState()` on launch ✓ |
| Voice session | ❌ Not persisted | Not restored — requires user re-tap |
| Connector auth tokens | `ConnectorAuthManager` SharedPreferences ✓ | On `ConnectorsScreen` open ✓ |
| Streaming text (in-flight) | ❌ Not persisted | Lost — blank state on relaunch |
| Nav back stack depth | Compose NavController SavedState ✓ | Restored by NavHost ✓ |

---

## Coroutine Leak Analysis

All coroutines in the UI layer use `viewModelScope` or `rememberCoroutineScope()`.
**No `GlobalScope.launch` calls found in the UI layer.**

`RuntimeHealthMonitor`, `ExecutionWatchdog`, and `ConnectorHealthMonitor` all use
their own `CoroutineScope(SupervisorJob())` — correct for process-lifetime singletons.

`EventBus` uses a `CoroutineScope(SupervisorJob())` — correct.

**Overall coroutine hygiene: GOOD.** The one gap (H2-1) is that ChatViewModel
does not register its coroutines with `RuntimeHealthMonitor`, so orphan detection
is blind to them.
