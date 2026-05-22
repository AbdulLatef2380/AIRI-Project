# AIRI Stability Audit — Phase H2
*Static analysis of actual source. No Android SDK available for runtime profiling.*

---

## What Exists (Real Monitoring Infrastructure)

### RuntimeHealthMonitor
- **Heap check**: warns if `availableHeap < 80 MB` (LOW_MEMORY_MB constant)
- **Disk check**: warns if files-dir free space < 100 MB
- **Network**: live `ConnectivityManager.NetworkCallback`
- **Session age**: warns after configurable threshold (long-session detection)
- **Coroutine tracking**: `registerCoroutine` / `unregisterCoroutine` API — but **ChatViewModel does NOT call these**, so orphan tracking is blind to the most active coroutine producer
- **Agent task tracking**: `recordAgentStart` / `recordAgentEnd` — called by ExecutionWatchdog
- **Event bus rate**: emit vs drain counter

### ExecutionWatchdog
- Scans stuck plans every 60s
- Reports to `RuntimeHealthMonitor.recordAgentEnd` when plans complete
- `autoCancelStuck = false` — stuck plans are logged but NOT auto-cancelled

### CrashReportStore + RuntimeRecoveryEngine
- Persists crash timestamp on process death via UncaughtExceptionHandler
- Recovers from prior crash on next launch

---

## Real Stability Gaps

### H2-1: ChatViewModel coroutines not tracked by RuntimeHealthMonitor
**Severity: HIGH**
`ChatViewModel` launches coroutines via `viewModelScope.launch()` for:
- streaming generation (long-lived during response)
- voice transcript collection (`collect`)
- smart reply generation
- memory saving

None of these call `ServiceLocator.runtimeHealthMonitor.registerCoroutine()`.
The health monitor's coroutine leak detection is therefore blind to the app's most active coroutine source.

**Fix needed**: Call `registerCoroutine("chat_stream_$id")` at launch and `unregisterCoroutine()` in the finally block of each major coroutine in ChatViewModel.

### H2-2: Local model memory pressure — no low-memory eviction
**Severity: HIGH**
`LlamaManager` holds a native JNI model in memory. When Android delivers `onLowMemory()` or `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`:
- There is no callback in `AIRIApplication` or `MainActivity` to unload the model
- The process will be killed by the OS instead of gracefully releasing native memory
- On next launch, the model must be fully re-loaded (5–30 seconds depending on size)

**Fix needed**: `AIRIApplication` should override `onTrimMemory()` and call `LlamaManager.unloadModel()` at `TRIM_MEMORY_RUNNING_CRITICAL` or higher.

### H2-3: Vosk model held in memory alongside LlamaManager
**Severity: MEDIUM**
`VoskEngine` holds a `org.vosk.Model` (~150–400 MB depending on model size) in native memory simultaneously with the LlamaManager GGUF model. On devices with 3–4 GB RAM running a 4B+ parameter GGUF, both cannot coexist without pressure.

**Fix needed**: `VoiceSettingsScreen` download installs the small model (~40 MB Vosk) which is manageable. The issue is worst if user downloads a large Vosk model. No guard exists.

### H2-4: AudioFocus request failure not handled
**Severity: MEDIUM**
`DuplexConversationRuntime` (LiveVoiceService) requests `AUDIOFOCUS_GAIN` but the result `AUDIOFOCUS_REQUEST_FAILED` path does nothing visible to the user. Voice session silently fails to start when another app holds audio focus (active call, Maps navigation).

### H2-5: No stream watchdog coroutine separate from withTimeout
**Severity: MEDIUM**
The current `withTimeout(90_000L)` in `streamRemoteResponse` is a hard deadline. There is no first-token timeout — a connection that establishes but sends no tokens for 30 seconds still holds the generating state for the remaining ~60 seconds before the user sees feedback. A first-token watchdog at 15s would dramatically improve perceived responsiveness.

### H2-6: ViewModelScope coroutines survive screen recreation
**Severity: LOW**
`ChatViewModel` is a `ViewModel` — it survives configuration changes. Its coroutines in `viewModelScope` are correctly tied to ViewModel lifecycle, not Activity. **This is correct behavior.** However, the voice transcript collector launched in `viewModelScope` runs continuously whether the voice session is active or not. If the user navigates away from the chat screen and back, the collector is not duplicated (ViewModel is reused). This is safe.

### H2-7: WorkManager Doze deferral
**Severity: LOW**
`ScheduledJobOrchestrator.scheduleOnce()` uses `setInitialDelay()`. Under Android Doze mode, WorkManager may defer execution by up to 10+ minutes. The `AgentTasksScreen` shows `triggerAtMs` as the expected time but the actual execution may be significantly later. No user-visible indication of this deferral exists.

---

## Lifecycle Correctness — What Is Right

- `ViewModel` is correctly used; no `Activity` references in ChatViewModel
- All coroutines use `viewModelScope` (not GlobalScope) in UI layer ✓
- `LiveVoiceService` is a foreground service — survives app backgrounding ✓
- `AgentWorker` uses `CoroutineWorker` — WorkManager handles lifecycle ✓
- `RuntimeHealthMonitor` uses its own `SupervisorJob` — does not leak into Activity ✓

---

## Validation Scripts

See `scripts/validate_stability.sh` for automated checks of the above.
