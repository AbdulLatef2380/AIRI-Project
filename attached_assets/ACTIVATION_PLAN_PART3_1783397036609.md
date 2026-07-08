# AIRI ACTIVATION PLAN — PART 3
## Tech Debt · Bug Fixes · Scalability · Phase 3+ Preparation
**Wave 4 + Wave 5 items | P2–P3 | Days 22–Post**

> Builds on Parts 1 and 2. Most items in this part are independent of each other and can be executed in parallel.

---

## AP-SS — AGENTSANDBOX ENFORCEMENT (ToolDispatcher Bypass Closure)

### Current State
**Status:** Partially Active — Critical Security Gap. `AgentSandbox` is implemented and wired to `AgentWorker` but NOT to `AgentLoop`. `ToolDispatcher` can dispatch tool calls without passing through `AgentSandbox`, bypassing the sandbox layer entirely.

**Why Not Active:**
The Inventory documents: "`AgentSandbox` — not called by `AgentLoop` directly; `ToolDispatcher` can bypass the sandbox layer." `AgentLoop` calls `toolDispatcher.dispatch(toolCall)` directly. `AgentSandbox.execute(action, context)` is only called by `AgentWorker` (background worker path). The primary synchronous chat execution path has zero sandbox coverage.

### Activation Path
```
Locate AgentLoop.run() — identify where toolDispatcher.dispatch() is called
    ↓
Wrap each tool dispatch in AgentSandbox.execute():
    Before: toolDispatcher.dispatch(toolCall)
    After:  agentSandbox.execute(toolCall, context) { toolDispatcher.dispatch(toolCall) }
    ↓
ServiceLocator.kt — verify agentSandbox is a singleton; pass to AgentLoop constructor if not already
    ↓
Verify: AgentSandbox.execute() applies the same allowlist + rate limiting as ExecutionFirewall
    ↓
Security test: tool call from AgentLoop path → sandbox gate fires → blocked tool rejected
    ↓
Security test: all existing tool calls still succeed when sandbox allows
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `agent/loop/AgentLoop.kt` | Wrap all `toolDispatcher.dispatch()` calls inside `agentSandbox.execute()` |
| `ServiceLocator.kt` | Pass `agentSandbox` to `AgentLoop` constructor if not already present |

### Exact Wiring Pattern
```kotlin
// AgentLoop.kt — verify constructor has agentSandbox:
class AgentLoop(
    private val toolDispatcher: ToolDispatcher,
    private val agentSandbox: AgentSandbox,  // ADD if absent
    // ... existing params
)

// In AgentLoop.run(), wherever tool calls are dispatched:
// BEFORE:
val result = toolDispatcher.dispatch(toolCall)

// AFTER:
val result = agentSandbox.execute(
    action = toolCall.name,
    agentId = currentAgentId,
    context = executionContext
) {
    toolDispatcher.dispatch(toolCall)
}
// If agentSandbox.execute() returns GovernanceDecision.BLOCK → tool call rejected
// If ALLOW → toolDispatcher.dispatch() runs normally
```

### Dependency Activation Graph
```
AP-SS Activated
    ↓
ALL tool calls from AgentLoop now pass through AgentSandbox
    ↓
ExecutionFirewall + AgentSandbox form two layers of enforcement
    ↓
AP-09 (encoding bypass fix) now covers the full dispatch path (not just PermissionGovernanceLayer)
    ↓
AP-21 (WorldRiskProvider) world-aware risk evaluation covers AgentLoop tool calls
    ↓
FULL_AGENT mode tool execution is sandbox-enforced end-to-end
    ↓
Security posture: +5 points
```

### Ripple Effect
**2 files** modified (`AgentLoop.kt`, `ServiceLocator.kt`). No new classes — `AgentSandbox` already exists. Verify that `AgentSandbox.execute()` is non-blocking (should return quickly for ALLOW; only incur overhead on BLOCK).

### Testing Strategy
```
Security tests:
1. Submit tool call from AgentLoop with forbidden tool name → AgentSandbox rejects → result is GovernanceDecision.BLOCK
2. Submit tool call from AgentLoop with allowed tool name → AgentSandbox passes → ToolDispatcher executes normally

Regression tests:
3. All existing tool call types (AlarmTool, CalendarTool, NotesTool, SearchTool) still succeed
4. Rate limit enforcement: >60 tool calls/min from AgentLoop → rate limiter fires → excess calls rejected
5. No performance regression on normal tool calls (sandbox overhead < 5ms per call)

Integration test:
6. End-to-end: user sends message → AgentLoop runs → tool called → sandbox gate confirmed active in AuditLog
```

### Rollback Strategy
Remove `agentSandbox.execute()` wrapper from `AgentLoop`. No data changes.

### Definition of Done
- [ ] `AgentLoop.run()` wraps ALL `toolDispatcher.dispatch()` calls inside `agentSandbox.execute()`
- [ ] Zero `toolDispatcher.dispatch()` calls in `AgentLoop` that bypass `agentSandbox`
- [ ] `agentSandbox` passed to `AgentLoop` via constructor (no direct `ServiceLocator.agentSandbox` access inside loop)
- [ ] Blocked tool call: returns `GovernanceDecision.BLOCK`; audit log entry written
- [ ] Allowed tool call: dispatches normally; no latency regression (< 5ms overhead)
- [ ] All existing tool call regression tests pass
- [ ] Rate limit enforcement verified on AgentLoop path

---

## AP-26 — SERVER-SIDE SIGN-OUT TOKEN REVOCATION

### Current State
**Status:** Backend Only (backend missing). Firebase ID tokens remain valid for up to 60 minutes after client-side sign-out.

**Why Not Active:**
`AuthService.signOut()` calls `firebaseAuth.signOut()` and `secureStorage.clear()` but does NOT revoke the Firebase ID token on the backend. A token intercepted before sign-out continues to authorize API requests for up to 60 minutes. Backend endpoint `api.airi-assistant.app/auth/revoke` does not exist.

### Activation Path
```
Backend work: CREATE api.airi-assistant.app/auth/revoke endpoint
    (Accept: { uid: String }, verify Firebase Admin SDK, revoke all tokens for uid)
    ↓
domain/auth/AuthService.kt — add pre-wipe revocation call
    ↓
Log revocation failure to AuditRepository (do NOT block local sign-out on backend failure)
    ↓
Integration test: sign out → immediately attempt API call with old token → rejected
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `domain/auth/AuthService.kt` | Add `apiClient.post("/auth/revoke", RevokeRequest(uid))` before local sign-out; catch and log failure |

### Exact `AuthService.signOut()` Change
```kotlin
suspend fun signOut() {
    val uid = firebaseAuth.currentUser?.uid
    // Step 1: Server-side token revocation (best-effort, non-blocking for local sign-out)
    if (uid != null) {
        try {
            apiClient.post("/auth/revoke", RevokeRequest(uid = uid))
            auditRepository.log("AUTH_REVOKE_SUCCESS", "Token revoked server-side for uid=$uid")
        } catch (e: Exception) {
            // Do NOT block local sign-out if backend is unreachable
            auditRepository.log("AUTH_REVOKE_FAILED", "Backend revocation failed: ${e.message}", LogLevel.WARN)
        }
    }
    // Step 2: Local sign-out (always executes regardless of step 1 outcome)
    firebaseAuth.signOut()
    secureStorage.clear()
}
```

### Dependency Activation Graph
```
Backend endpoint live: api.airi-assistant.app/auth/revoke
    ↓
AP-26 Activated
    ↓
Sign-out invalidates token immediately (not after 60-minute expiry)
    ↓
Security posture: +3 points (token exfiltration window eliminated)
```

### Ripple Effect
**1 file** modified (`AuthService.kt`). **1 backend endpoint** created (backend work, not in this codebase).

### Testing Strategy
```
Integration test:
1. Sign in → capture current Firebase ID token
2. Sign out (AP-26 wired)
3. Immediately attempt API call with captured token → 401 Unauthorized (token revoked)
4. Without AP-26: same token → 200 OK for up to 60 minutes

Backend failure:
5. Mock backend as unreachable → sign out still completes locally
6. AuditRepository logs AUTH_REVOKE_FAILED
7. firebaseAuth.signOut() + secureStorage.clear() both execute regardless
```

### Rollback Strategy
Remove the revocation call from `AuthService.signOut()`. No data changes.

### Definition of Done
- [ ] Backend endpoint `api.airi-assistant.app/auth/revoke` live and tested
- [ ] `AuthService.signOut()` calls revoke before local sign-out
- [ ] Backend failure does NOT block local sign-out
- [ ] `AuditRepository` logs success or failure of revocation
- [ ] Integration test: old token rejected immediately after sign-out

---

## AP-27 — PLAY INTEGRITY VERIFIER BACKEND CONFIRMATION

### Current State
**Status:** Partially Active. `PlayIntegrityVerifier` exists. Backend endpoint status unknown.

**Why Not Active:**
`PlayIntegrityVerifier.issue()` calls the Google Play Integrity API and receives a verdict token. This token must be verified server-side at `api.airi-assistant.app/integrity/verify`. If the backend does not exist, the entire integrity flow is dead even though the Android client is implemented.

### Activation Path — Two Paths Based on Backend Status
```
STEP 1: Confirm whether api.airi-assistant.app/integrity/verify is live
    ↓
IF NOT LIVE:
    Disable PlayIntegrityVerifier.issue() in AIRIApplication.onCreate() with explicit TODO:
    // TODO: Enable when integrity backend is live at /integrity/verify
    // val integrityVerifier = PlayIntegrityVerifier(context) — DISABLED
    ↓
    Log "PLAY_INTEGRITY_DISABLED" to AuditRepository
    ↓
    Ensure FULL_AGENT mode does NOT depend on integrity verdict when verifier is disabled

IF LIVE:
    Wire PlayIntegrityVerifier.verifyWithBackend(token) → on MEETS_VIRTUAL_INTEGRITY failure:
        Disable FULL_AGENT mode
        Disable accessibility execution
        Show dialog: "Device integrity check failed — some features are restricted"
    ↓
    Integration test: genuine device → MEETS_DEVICE_INTEGRITY → FULL_AGENT available
    Integration test: emulator → MEETS_VIRTUAL_INTEGRITY → FULL_AGENT restricted
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `integrity/PlayIntegrityVerifier.kt` | Wire `verifyWithBackend()` result to downstream gatekeeping (if backend live) |
| `AIRIApplication.kt` | Enable or explicitly disable `PlayIntegrityVerifier.issue()` based on backend status |
| `ui/viewmodel/ChatViewModel.kt` | Gate `FULL_AGENT` on integrity verdict when verifier is enabled |

### Definition of Done
- [ ] Backend status explicitly confirmed (documented in `replit.md` or code comment)
- [ ] If live: integrity verdict gates `FULL_AGENT` mode; genuine device passes; emulator restricted
- [ ] If not live: verifier explicitly disabled with TODO; no dead code silently failing

---

## AP-28 — SPECULATIVEMANAGER ADAPTIVE FEEDBACK

### Current State
**Status:** Partially Active — tracking active, adaptation dead.

**Why Not Active:**
`SpeculativeManager.getAcceptanceRate()` returns `acceptedTokens / totalTokens` but the return value is never read by any caller. If acceptance rate drops below 0.4, speculative decoding is wasting memory and CPU generating draft tokens that are rejected — with no benefit to throughput.

### Activation Path
```
ai/LlamaManager.kt — in generate() loop, every 50 tokens: check acceptance rate
    ↓
If rate < 0.35f: speculativeManager.disable()
    ↓
Log: auditRepository.log("SPECULATIVE_DISABLED", "Rate: $rate < 0.35 threshold")
    ↓
Re-enable on next model swap (speculativeManager.enable() in onModelLoaded())
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ai/LlamaManager.kt` | Add acceptance rate check every 50 decoded tokens in `generate()` loop |

### Exact Change
```kotlin
// In LlamaManager.generate() — add inside the token decode loop:
var tokensSinceCheck = 0
// Inside the loop:
tokensSinceCheck++
if (tokensSinceCheck >= 50) {
    tokensSinceCheck = 0
    if (speculativeManager.isEnabled()) {
        val rate = speculativeManager.getAcceptanceRate()
        if (rate < 0.35f) {
            speculativeManager.disable()
            ServiceLocator.auditRepository.logSync(
                "SPECULATIVE_DISABLED",
                "Acceptance rate $rate below 0.35 threshold — speculative decoding paused"
            )
        }
    }
}
```

### Dependency Activation Graph
```
AP-28 Activated
    ↓
LlamaManager automatically disables speculative decoding when it hurts performance
    ↓
Memory freed for useful KV cache on poor-acceptance models
    ↓
Inference throughput may improve on models with low draft acceptance
```

### Ripple Effect
**1 file** (`LlamaManager.kt`). ~10 lines added in the generate loop.

### Definition of Done
- [ ] `speculativeManager.getAcceptanceRate()` read every 50 tokens
- [ ] `speculativeManager.disable()` called when rate < 0.35f
- [ ] AuditRepository logs the disable event with current rate
- [ ] Speculative decoding re-enabled on model swap

---

## AP-29 — PLANNER ADAPTATION ENGINE RE-ENABLE

### Current State
**Status:** Stub. Deliberately disabled. Activation condition: `AuditRepository.getCount("PLAN_EXECUTION") > 1000`.

**Why Not Active:**
`PlannerAdaptationEngine` is null-stubbed in `UnifiedCognitiveLoop.kt` line 117 with comment "DISABLED Phase 1 — requires production data collection." This was an intentional deferral. The engine improves plan quality by learning from past execution outcomes. It requires a baseline of 1000+ PLAN_EXECUTION audit entries to function correctly.

### Activation Path (when condition is met)
```
STEP 1: Confirm AuditRepository.getCount("PLAN_EXECUTION") > 1000
    (Check via DeveloperCenterScreen or direct DB query)
    ↓
STEP 2: core/UnifiedCognitiveLoop.kt line 117:
    Before: private val adaptationEngine: PlannerAdaptationEngine? = null
    After:  private val adaptationEngine = PlannerAdaptationEngine(ServiceLocator.auditRepository)
    ↓
STEP 3: Wire ExecutionReflector.reflect() output → adaptationEngine.recordOutcome()
    ↓
STEP 4: Wire adaptationEngine.getSuggestions() → PlanGenerator.createDAGPlanFromLLM() as optional hints
    ↓
STEP 5: Monitor for 2 weeks:
    - Plan success rate (should improve or hold steady)
    - Average step count (should decrease for equivalent tasks)
    - Retry frequency (should decrease)
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `core/UnifiedCognitiveLoop.kt` | Line 117: un-null `adaptationEngine`; wire `recordOutcome()` and `getSuggestions()` |
| `ServiceLocator.kt` | Pass `auditRepository` to `PlannerAdaptationEngine` constructor |

### Definition of Done
- [ ] `AuditRepository.getCount("PLAN_EXECUTION") > 1000` confirmed before enabling
- [ ] `PlannerAdaptationEngine` un-stubbed in `UnifiedCognitiveLoop`
- [ ] `recordOutcome()` called after `ExecutionReflector.reflect()` for every plan
- [ ] `getSuggestions()` wired as optional hints to `PlanGenerator`
- [ ] 2-week monitoring plan in place (plan quality metrics logged)

---

## AP-33 — UILEARNING FEEDBACK LOOP

### Current State
**Status:** Partially Active. Write path active. Read path absent. Learned UI patterns accumulate but are never used.

**Why Not Active:**
`UILearningEngine.storeLearnedNode(package, intent, node)` writes learned UI interactions to storage. `SmartActionEngine.resolveClick(intent, packageName)` does not read from `UILearningEngine`. The learned data has zero influence on future interactions.

### Activation Path
```
accessibility/SmartActionEngine.kt — add Tier-0 lookup from UILearningEngine before existing Tier-1
    ↓
On match: return the learned accessibility node directly (skip Tier-1 and Tier-2)
    ↓
On no match: fall through to existing Tier-1 (Memory), Tier-2 (Heuristic) lookup
    ↓
Integration test: perform action in app → learn node → restart → perform same action → uses learned path
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `accessibility/SmartActionEngine.kt` | Add Tier-0 `UILearningEngine` lookup at top of `resolveClick()` |

### Exact Change
```kotlin
// SmartActionEngine.resolveClick(intent: ActionIntent, packageName: String):
// ADD before existing Tier-1 lookup:

// Tier-0: Learned nodes (from UILearningEngine)
val learnedNodes = uiLearningEngine.getLearnedNodes(packageName)
val learnedMatch = learnedNodes.firstOrNull { node ->
    node.intentSignature == intent.signature
}
if (learnedMatch != null) {
    auditRepository.logSync("UI_LEARNED_HIT", "Tier-0 match: ${intent.signature} in $packageName")
    return learnedMatch.accessibilityNode
}

// Tier-1 (existing): Memory-based lookup
// Tier-2 (existing): Heuristic lookup
// ...
```

### Dependency Activation Graph
```
AP-33 Activated
    ↓
UILearningEngine write path now has a consumer
    ↓
SmartActionEngine improves over time as it learns app-specific UI patterns
    ↓
Accessibility automation becomes more reliable and faster (cached learned paths)
    ↓
Feature 05 (Accessibility & UI Automation) is now fully active
```

### Ripple Effect
**1 file** (`SmartActionEngine.kt`). ~10 lines added. `UILearningEngine` is not modified — existing write path used as-is.

### Testing Strategy
```
Integration test:
1. Perform a tap action in a third-party app → UILearningEngine stores node
2. Trigger same action again → SmartActionEngine returns learned node (Tier-0 hit logged)
3. Tier-0 cache miss (new action) → falls through to Tier-1/Tier-2 unchanged

Unit test:
4. getLearnedNodes returns node with matching intentSignature → resolveClick returns that node
5. getLearnedNodes returns empty → falls through to Tier-1 (existing test coverage)
```

### Definition of Done
- [ ] `SmartActionEngine.resolveClick()` checks `UILearningEngine.getLearnedNodes()` before Tier-1
- [ ] Tier-0 match returns immediately without Tier-1/Tier-2 lookup
- [ ] AuditRepository logs `UI_LEARNED_HIT` events
- [ ] Integration test: second invocation of same action uses learned path

---

## AP-36 — HARDWARE PROFILER BATTERY FACTOR

### Current State
**Status:** Partially Active. `HardwareProfiler.profile()` returns static tier. Battery state not considered.

**Why Not Active:**
`HardwareProfiler` runs once at startup and computes `HardwareTier` based on CPU, RAM, and GPU benchmarks. Battery level and `PowerManager.isPowerSaveMode` are never checked. A FLAGSHIP device at 5% battery in power save mode will run the most compute-intensive models — draining the battery in minutes.

### Activation Path
```
runtime/HardwareProfiler.kt — add battery check in profile()
    ↓
Read: BatteryManager.BATTERY_PROPERTY_CAPACITY + PowerManager.isPowerSaveMode
    ↓
Apply downgrade logic: isPowerSave || battery < 10% → LOW; battery < 20% → downgrade one tier
    ↓
Register BroadcastReceiver for ACTION_BATTERY_CHANGED
    ↓
On crossing 20% threshold: re-run profile() and update ServiceLocator.hardwareTier
    ↓
LlamaManager reads updated hardwareTier for model tier decisions
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `runtime/HardwareProfiler.kt` | Add battery/power-save factor to `profile()`; add re-profile on battery threshold |
| `AIRIApplication.kt` | Register battery change `BroadcastReceiver` |

### Exact `profile()` Change
```kotlin
fun profile(context: Context): HardwareTier {
    val baseTier = computeHardwareTier() // existing logic

    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val isPowerSave = powerManager.isPowerSaveMode

    return when {
        isPowerSave || batteryPct < 10 -> HardwareTier.LOW
        batteryPct < 20 -> baseTier.downgradeOne()  // FLAGSHIP→HIGH, HIGH→MEDIUM, etc.
        else -> baseTier
    }
}

// BroadcastReceiver in AIRIApplication:
private val batteryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = (level * 100 / scale)
        if (pct <= 20) {
            // Re-profile with current battery state
            ServiceLocator.hardwareTier = HardwareProfiler().profile(context)
        }
    }
}

// In AIRIApplication.onCreate():
registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
```

### Ripple Effect
**2 files** modified. `HardwareTier.downgradeOne()` must exist or be added as an extension (1–5 lines).

### Definition of Done
- [ ] `profile()` checks `isPowerSaveMode` and `BATTERY_PROPERTY_CAPACITY`
- [ ] Power save or <10% battery → `HardwareTier.LOW` (use lightest model)
- [ ] 10–20% battery → tier downgraded by one level
- [ ] Battery change receiver triggers re-profile at 20% threshold
- [ ] `ServiceLocator.hardwareTier` updated on re-profile

---

## BUG FIX BATCH (B-01 through B-11)

These are all very small fixes (1–15 lines each). Execute as a single sprint.
**Note on IDs:** These are B-series items (bugs), not AP-series. AP-30 through AP-35 are the deferred Wave-5 scaffold items defined later in this document.

---

### B-01 — RetentionManager Duplicate WorkManager Enqueue

**File:** `domain/growth/RetentionManager.kt`
**Problem:** `WorkManager.enqueue(request)` allows duplicate workers to be created, resulting in multiple reengagement notifications.

```kotlin
// BEFORE:
WorkManager.getInstance(context).enqueue(request)

// AFTER:
WorkManager.getInstance(context).enqueueUniqueWork(
    "reengagement_notification",
    ExistingWorkPolicy.KEEP,
    request
)
```

**Test:** Launch app twice → only one reengagement notification work request exists in WorkManager.

---

### B-02 — SessionManager Exponential Backoff on Token Refresh Failure

**File:** `domain/auth/SessionManager.kt` (or equivalent)
**Problem:** Token refresh failure loop retries at fixed interval — can flood the backend with rapid retry requests.

```kotlin
// In startRefreshLoop():
var backoffMs = 60_000L
while (isActive) {
    try {
        refreshToken()
        backoffMs = 60_000L // reset on success
        delay(55 * 60 * 1000L) // normal refresh interval (55 min)
    } catch (e: Exception) {
        auditRepository.logSync("TOKEN_REFRESH_FAILED", e.message ?: "unknown")
        delay(backoffMs)
        backoffMs = minOf(backoffMs * 2, 30 * 60 * 1000L) // cap at 30 min
    }
}
```

**Test:** Mock token refresh to fail 5 times → verify delay sequence: 1m → 2m → 4m → 8m → 16m → 30m (capped).

---

### B-03 — VoskEngine Rapid Start/Stop Guard

**File:** `voice/VoskEngine.kt`
**Problem:** Rapid start/stop calls may not release the audio recorder before the next session starts, causing audio resource conflicts.

```kotlin
// In VoskEngine.startListening():
if (isListening) {
    stopListening()
    delay(100) // brief settle time for audio resource release
}
// ... existing startListening logic
```

**Test:** Call `startListening()` twice rapidly → no crash; previous recorder released before new one starts.

---

### B-05 — DefaultAssistantManager Role-Loss Receiver

**File:** Create `domain/assistant/DefaultAssistantRoleLossReceiver.kt`
**Problem:** When another app becomes the default assistant, `DefaultAssistantManager` has no awareness. No notification is sent.

```kotlin
class DefaultAssistantRoleLossReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_APPLICATION_ROLE_CHANGED) {
            val role = intent.getStringExtra(RoleManager.EXTRA_ROLE_NAME)
            if (role == RoleManager.ROLE_ASSISTANT) {
                // Check if we lost the role:
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    // Show persistent notification with "Restore AIRI as assistant" action
                    NotificationHelper.showRoleLostNotification(context)
                }
            }
        }
    }
}

// Register in AndroidManifest.xml:
// <receiver android:name=".domain.assistant.DefaultAssistantRoleLossReceiver"
//           android:exported="false">
//     <intent-filter>
//         <action android:name="android.app.role.action.ROLE_CHANGED" />
//     </intent-filter>
// </receiver>
```

**Files:** New `DefaultAssistantRoleLossReceiver.kt`; `AndroidManifest.xml` (add receiver entry).

---

### B-06 — NotionMcpConnector Token In-Memory Caching

**File:** `connector/NotionMcpConnector.kt`
**Problem:** `SecureStorage.get("notion_integration_token")` called on every API request — unnecessary KeyStore decrypt operations.

```kotlin
// Add to NotionMcpConnector:
@Volatile private var cachedToken: String? = null
private var tokenCachedAt: Long = 0L
private val TOKEN_TTL_MS = 5 * 60 * 1000L // 5 minutes

private fun getToken(): String? {
    val now = System.currentTimeMillis()
    if (cachedToken != null && (now - tokenCachedAt) < TOKEN_TTL_MS) {
        return cachedToken
    }
    return secureStorage.get("notion_integration_token").also {
        cachedToken = it
        tokenCachedAt = now
    }
}

// Replace all secureStorage.get("notion_integration_token") calls with getToken()
```

**Test:** Invoke 10 Notion API calls → verify SecureStorage.get() called only once per 5-minute window.

---

### B-07 — RemoteModelRegistry Custom Endpoint Protection

**File:** `ai/remote/RemoteModelRegistry.kt`
**Problem:** Stale model ID migration incorrectly removes custom OpenAI-compatible endpoints (non-standard model IDs).

```kotlin
// In RemoteModelConfig data class — add:
data class RemoteModelConfig(
    val id: String,
    val name: String,
    val provider: ModelProvider,
    val isCustomEndpoint: Boolean = false,  // ADD THIS FIELD
    // ... existing fields
)

// In stale model migration logic — add guard:
val modelsToRemove = existingModels.filter { model ->
    !model.isCustomEndpoint &&  // ADD: never remove custom endpoints
    isStaleModelId(model.id)
}
```

**Test:** Add custom endpoint with non-standard model ID → run stale migration → custom endpoint still present.

---

### B-08 — DataDeletionCoordinator Firebase Token Expiry Fix

**File:** `domain/auth/DataDeletionCoordinator.kt`
**Problem:** `FirebaseUser.delete()` can fail with `ERROR_REQUIRES_RECENT_LOGIN` if the user's auth token is stale. Account deletion silently fails with no feedback.

```kotlin
// In deleteAccount(), before FirebaseAuth.delete():
val user = firebaseAuth.currentUser ?: return Result.failure(Exception("Not signed in"))

// Force-refresh token to ensure recent authentication:
try {
    user.getIdToken(/* forceRefresh= */ true).await()
} catch (e: FirebaseAuthRecentLoginRequiredException) {
    return Result.failure(Exception("Please sign in again to delete your account"))
}

// Proceed with deletion:
user.delete().await()
```

**Test:** Mock token as expired → `deleteAccount()` returns failure with re-login prompt → user sees actionable error.

---

### B-09 — ExperimentManager Thread Safety

**File:** `domain/experiments/ExperimentManager.kt`
**Problem:** `getOrAssignVariant()` reads and writes experiment assignments without synchronization — race condition in concurrent feature flag evaluations.

```kotlin
// Add @Synchronized to the assignment method:
@Synchronized
fun getOrAssignVariant(experimentId: String, variants: List<String>): String {
    // ... existing logic unchanged
}
```

**Test:** Call `getOrAssignVariant()` concurrently from 10 threads with same experiment ID → all return the same variant (no inconsistency).

---

### B-10 — SkillAuditLogger in Legacy SkillExecutor Path

**File:** `ai/skills/SkillExecutor.kt`
**Problem:** `SkillExecutor.tryHandle()` is deprecated and has zero production callers, but if ever revived, skill executions would be unaudited.

```kotlin
// In SkillExecutor.tryHandle() — add audit log call:
@Deprecated("Use SkillRuntime directly")
suspend fun tryHandle(input: SkillInput, context: SkillContext): SkillResult? {
    val result = // ... existing logic
    // ADD:
    skillAuditLogger.log(
        skillId = input.skillId,
        action = "execute_legacy",
        result = result?.let { "success" } ?: "no_match",
        context = context
    )
    return result
}
```

**Note:** If `tryHandle()` is confirmed to have zero callers and no revival plan, mark for deletion in the next dead code removal sprint instead.

---

### B-11 — OrchestratorCrashReporter Goal Text Sanitization

**File:** `crash/OrchestratorCrashReporter.kt`
**Problem:** Goal description (e.g., "book a restaurant for Friday night near my office") may be included verbatim in crash telemetry payloads.

```kotlin
// In crash report construction — truncate goal text:
val sanitizedGoal = goalDescription
    .take(20)
    .plus(if (goalDescription.length > 20) "..." else "")
    // Result: "book a restaurant fo..." (not "book a restaurant for Friday night near my office")

// In CrashReport:
crashReport.copy(goalDescription = sanitizedGoal)
```

**Test:** Crash with long goal text → telemetry payload contains truncated goal (≤23 chars including "...").

---

## AP-51 — EMBEDDINGDAO COSINE SCAN SCALABILITY

### Current State
**Status:** Functional but does not scale. `EmbeddingDao.getAll()` returns ALL rows for full-table cosine scan. At 10,000 stored messages: ~500ms per retrieval query. Degrades linearly.

**Why Not Active:**
`RagRetriever.retrieve(queryVector)` calls `embeddingDao.getAll()` which returns every stored embedding with no row limit. Cosine similarity is computed in-memory over the entire result set. This is fine at 500 messages but unusable at 10,000+ stored messages.

### Activation Path — Option C (Immediate, 1 day)
```
memory/dao/EmbeddingDao.kt — add getRecent(limit: Int = 5000) query
    ↓
memory/RagRetriever.kt — replace getAll() with getRecent(5000)
    ↓
Benchmark test: retrieval time ≤ 250ms with 10,000 stored embeddings
    ↓
Production Ready (immediate)
```

### Activation Path — Option A (Long-term, 3 days — recommended when time permits)
```
Add sqlite-vec dependency (arm64-v8a, armeabi-v7a, x86_64 native .so files)
    ↓
Room migration v6: CREATE VIRTUAL TABLE embeddings USING vec0(embedding FLOAT[N])
    ↓
Populate vector index from existing message_embedding rows on upgrade
    ↓
EmbeddingDao.findNearest(queryVector, limit=10) — @RawQuery KNN search
    ↓
RagRetriever.retrieve() — use findNearest() instead of getAll()
    ↓
Benchmark test: retrieval time ≤ 50ms with 50,000 stored embeddings
    ↓
Production Ready (long-term)
```

### Exact Files to Modify — Option C
| File | Change |
|:---|:---|
| `memory/dao/EmbeddingDao.kt` | Add `@Query("SELECT * FROM message_embedding ORDER BY timestamp DESC LIMIT :limit") fun getRecent(limit: Int): List<MessageEmbedding>` |
| `memory/RagRetriever.kt` | Replace `embeddingDao.getAll()` with `embeddingDao.getRecent(5000)` |

### Exact Option C Changes
```kotlin
// EmbeddingDao.kt — add:
@Query("SELECT * FROM message_embedding ORDER BY timestamp DESC LIMIT :limit")
suspend fun getRecent(limit: Int = 5000): List<MessageEmbedding>

// RagRetriever.kt — replace:
// BEFORE: val all = embeddingDao.getAll()
// AFTER:
val candidates = embeddingDao.getRecent(5000) // bounded: most recent 5,000 only
return cosineRankAndSlice(candidates, queryVector, topK = 10)
```

### Dependency Activation Graph
```
AP-51 (Option C) Activated
    ↓
RAG retrieval bounded at 5,000 rows: ~250ms max
    ↓
Memory & RAG feature (Feature 07) no longer degrades for heavy users
    ↓
AP-22 (MemoryExtractor auto-wiring): more facts stored → retrieval still fast
    ↓
AP-51 (Option A) when ready: KNN index → <50ms at 50,000 rows
```

### Ripple Effect — Option C
**2 files** modified. Zero schema migration needed (query-only change, not schema change).

### Ripple Effect — Option A
**4 files** modified + 1 Room migration + native .so files bundled.

### Testing Strategy
```
Benchmark tests (required):
1. Option C: load 10,000 embeddings into test DB → getRecent(5000) → cosine rank → time ≤ 250ms
2. Option A: load 50,000 embeddings → findNearest() KNN query → time ≤ 50ms

Correctness:
3. Query most relevant stored fact → correct fact returned in top-3 results
4. getRecent() returns newest 5,000 rows (sorted by timestamp DESC) — not random sample
```

### Definition of Done — Option C (minimum)
- [ ] `embeddingDao.getAll()` not called from `RagRetriever` in production
- [ ] `embeddingDao.getRecent(5000)` used instead
- [ ] RAG retrieval time ≤ 250ms at 10,000 stored embeddings (benchmark test)

### Definition of Done — Option A (recommended long-term)
- [ ] sqlite-vec dependency bundled for all target ABIs
- [ ] Room migration v6: vector index created
- [ ] Existing embeddings indexed on upgrade (no data loss)
- [ ] `findNearest(queryVector, 10)` returns correct top-K results
- [ ] RAG retrieval time ≤ 50ms at 50,000 stored embeddings

---

## AP-52 — LEGACY TOOLREGISTRY / TOOLSCANNER DISPOSITION

### Current State
**Status:** Orphaned. `ToolScanner` discovers JSON tools from the filesystem. `ToolRegistry` holds them. Neither is wired to `AgentLoop` or `PromptService`. LLM has zero knowledge of filesystem-defined tools.

**Why Not Active:**
`ToolScanner.scan(externalFilesDir/AIRI/tools/)` runs but its output is never consumed by any production path. `ToolRegistry.getTools()` has zero callers outside its own package.

### Decision Required — Product Decision Must Come First

**Option A — Wire (if user-defined JSON tools are a product feature):**
```
Decision: wire filesystem tools as user-defined extensions
    ↓
Security review: validate JSON tool files before any execution (file injection attack surface)
    ↓
Add config flag: enable_legacy_tools = false (off by default; explicit opt-in)
    ↓
Add ToolRegistry.toToolSchema() extension — converts tool definitions to LLM tool schema format
    ↓
Add ToolRegistry.toToolDispatcher() extension — creates a ToolDispatcher from JSON tools
    ↓
PromptService.buildSystemPrompt(): if (flag) append toolRegistry.getTools().toToolSchema()
    ↓
AgentLoop.run(): dispatchers = listOf(toolDispatcher, toolRegistry.toToolDispatcher())
    ↓
Security test: malicious JSON tool file → schema-validated and rejected before execution
    ↓
Production Ready
```

**Option B — Delete (recommended if no roadmap for user-defined filesystem tools):**
```
Decision: delete orphaned scanner code
    ↓
grep -r "ToolScanner|ToolRegistry" --include="*.kt" app/src/ → verify zero callers outside own package
    ↓
Delete: tools/registry/ToolRegistry.kt, tools/registry/ToolScanner.kt
    ↓
Full build → zero compile errors
    ↓
Done
```

### Files to Modify / Delete
**Option A:**
| File | Action |
|:---|:---|
| `tools/registry/ToolRegistry.kt` | Add `toToolSchema()` and `toToolDispatcher()` extensions |
| `ai/prompt/PromptService.kt` | Add conditional tool schema inclusion |
| `agent/loop/AgentLoop.kt` | Add toolRegistry dispatcher to dispatch list |
| `ExecModePreferences.kt` or `ServiceLocator.kt` | Add `enable_legacy_tools` config flag (default: false) |

**Option B:**
| File | Action |
|:---|:---|
| `tools/registry/ToolRegistry.kt` | DELETE |
| `tools/registry/ToolScanner.kt` | DELETE |

### Security Note (Option A Only)
External tool loading from `externalFilesDir` is a file injection attack surface. Before any execution:
1. JSON tool file must pass schema validation (required fields, no `$execute` injection)
2. Tool names must match a whitelist of allowed operations (no shell execution, no file writes outside sandbox)
3. Each JSON tool invocation must pass through `ExecutionFirewall` before dispatch

### Verification Command (Both Options — Run First)
```bash
grep -rn "ToolScanner\|ToolRegistry" app/src/main/java --include="*.kt" \
    | grep -v "class ToolScanner\|class ToolRegistry\|object ToolScanner\|object ToolRegistry\|// "
```
Must return zero results for Option B to be safe.

### Definition of Done — Option A
- [ ] Product decision documented
- [ ] Security review complete for JSON tool loading
- [ ] `enable_legacy_tools = false` by default; opt-in required
- [ ] JSON tool schema validated before execution
- [ ] LLM can invoke user-defined tools when flag enabled

### Definition of Done — Option B
- [ ] Zero callers confirmed via grep
- [ ] `ToolRegistry.kt` deleted; `ToolScanner.kt` deleted
- [ ] Full build: zero compile errors
- [ ] No orphaned references in any other file

---

## AP-49 — VOICE PREFERENCES CLOUD SYNC

### Current State
**Status:** Architecture Only. `VoicePreferencesStore` TTS personality presets are local-only.

**Depends on:** AP-20 (PreferenceCoordinator must include VoicePreferencesStore first).

### Activation Path
```
AP-20 completes (VoicePreferencesStore in PreferenceCoordinator)
    ↓
sync/CloudSyncCoordinator.kt — include VoicePreferencesStore.getAllPresets() in sync payload
    ↓
Include voice prefs in GDPR export (when ChatExporter is triggered)
    ↓
Multi-device: voice presets restore on second device after sync
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `sync/CloudSyncCoordinator.kt` | Add voice prefs to sync payload; add voice prefs to GDPR export |

### Exact Change
```kotlin
// CloudSyncCoordinator.buildSyncPayload():
val syncPayload = SyncPayload(
    execPrefs = preferenceCoordinator.getExecPrefs(),
    uiState = preferenceCoordinator.getUiState(),
    languageSettings = preferenceCoordinator.getLanguageSettings(),
    themeMode = preferenceCoordinator.getThemeMode(),          // from AP-20
    activeVoiceModel = preferenceCoordinator.getActiveVoiceModel(), // from AP-20
    voicePresets = voicePreferencesStore.getAllPresets()        // ADD THIS
)
```

### Definition of Done
- [ ] `CloudSyncCoordinator` sync payload includes voice presets
- [ ] Voice presets restore on second device after sync
- [ ] Voice presets included in GDPR export

---

## AP-50 — TOKEN ACCOUNTANT DYNAMIC PRICING

### Current State
**Status:** Partially Active. `TokenAccountant` pricing tables hardcoded (may be stale).

**Depends on:** Backend endpoint `api.airi-assistant.app/pricing` must be live.

### Activation Path
```
Confirm api.airi-assistant.app/pricing endpoint exists and returns pricing JSON
    ↓
domain/accounting/TokenAccountant.kt — fetch pricing on startup; cache 24h; fallback to hardcoded
    ↓
Pricing format: { "openai": { "gpt-4o": { "input": 0.0025, "output": 0.01 }, ... }, ... }
    ↓
On network failure: use hardcoded rates; log PRICING_FETCH_FAILED to AuditRepository
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `execution/accounting/TokenAccountant.kt` | Add pricing fetch at startup; 24h cache; fallback to hardcoded rates |

### Exact Implementation
```kotlin
// TokenAccountant.kt — add:
private var cachedPricing: PricingTable? = null
private var pricingFetchedAt: Long = 0L
private val PRICING_TTL_MS = 24 * 60 * 60 * 1000L

suspend fun ensurePricingCurrent() {
    val now = System.currentTimeMillis()
    if (cachedPricing != null && (now - pricingFetchedAt) < PRICING_TTL_MS) return
    try {
        val response = apiClient.get("/pricing")
        cachedPricing = Json.decodeFromString<PricingTable>(response.body)
        pricingFetchedAt = now
        auditRepository.logSync("PRICING_FETCHED", "Pricing table updated from API")
    } catch (e: Exception) {
        auditRepository.logSync("PRICING_FETCH_FAILED", e.message ?: "unknown", LogLevel.WARN)
        // cachedPricing remains null → hardcoded rates used as fallback
    }
}

fun getPricePerToken(provider: ModelProvider, model: String, type: TokenType): Double {
    return cachedPricing?.getPrice(provider, model, type)
        ?: HARDCODED_PRICES[provider]?.get(model)?.get(type)  // fallback
        ?: 0.0 // free/unknown
}
```

### Definition of Done
- [ ] Pricing fetched from `api.airi-assistant.app/pricing` on startup
- [ ] Pricing cached 24h; refetched on expiry
- [ ] Network failure falls back to hardcoded rates (no crash, no null prices)
- [ ] `AuditRepository` logs successful fetch and failed fetch

---

## WAVE 5 — PHASE 3+ SCAFFOLDS (Planned; Not Yet Due)

These items are documented for completeness. They are deferred by design and should NOT be activated until the specific conditions noted below are met.

---

### AP-30 — PHASE 9 GRAPH-NATIVE EXECUTION ENGINE

**Activation condition:** `PlannerAdaptationEngine` (AP-29) has collected 90+ days of execution data. Quality metrics show UCL baseline is well-understood.

**When to activate:**
1. Instantiate `ExecutionGraphRuntime` as alternative UCL backend
2. A/B test 5% of ACTION intents
3. Compare 4-week quality metrics against UCL baseline
4. Promote if quality ≥ UCL baseline on all metrics (success rate, avg steps, retry frequency)

**Files:** `agent/execution/runtime/ExecutionGraphRuntime.kt`, `agent/execution/runtime/AdaptiveGraphEngine.kt` — both exist as scaffolds; no changes needed until activation.

---

### AP-31 — MCP SERVER FOR DESKTOP CLI

**When to activate:** User research confirms demand for desktop integration via MCP protocol.

**Required new infrastructure:**
- `system/McpServerService.kt` — Android foreground service
- Ktor HTTP server on localhost:8080
- MCP protocol handshake
- Tool-call routing to `ServiceLocator.toolDispatcher`
- Toggle in `DeveloperCenterScreen`

---

### AP-32 — VNC / REMOTE DESKTOP

**Activation condition:** AP-05 (BiometricGatekeeper) complete; AP-31 (MCP server) complete.

**Required new infrastructure:**
- `MediaProjection` API for screen capture
- `AccessibilityService` for input injection
- WebSocket server
- Per-session TLS
- Biometric consent gate per session

**Files:** `RemoteDesktopManager.kt`, `VncProtocolHandler.kt` — currently empty scaffolds.

---

### AP-34 — ACTIONABLE DAG NODES

**When to activate:** Wave 4 complete; user research confirms demand for plan step intervention.

**Required changes:**
- Long-press context menu on `PlanStepChip` in `AgentControlScreen`
- Options: Retry, Skip, Edit Input
- `UCL.retryNode(nodeId)` and `UCL.skipNode(nodeId)` — implement in `UnifiedCognitiveLoop`
- Status updates via `ExecutionStatusBus`

---

### AP-35 — VIDEO / DOCUMENT ATTACHMENT PROCESSING

**When to activate:** Wave 3 complete; performance profiling confirms device headroom.

**Video:** `MediaMetadataRetriever` key frame extraction at 0%/50%/100% → `VisionImage.downscale()` for each frame → multimodal prompt.

**Document:** Route all document attachments through `DocumentProcessorAgent.extractText()` in primary chat path (not just ACTION intent path).

**Performance note:** Video key frame extraction is CPU-intensive. Profile on minimum target device before enabling in default path.

---

## GLOBAL ACTIVATION RIPPLE EFFECT SUMMARY

This table shows the complete cross-feature dependency graph. An item in the "Unblocks" column becomes actionable only after the item in the "Item" column completes.

| Item | Unblocks |
|:---|:---|
| AP-04 (SecureStorage singleton) | AP-02, AP-05, AP-06, AP-08, AP-10 |
| AP-02 (Database encryption) | Security posture 35 → 55+ |
| AP-05 (Biometric gatekeeper) | AP-32 (VNC consent gate) |
| AP-06 (Legacy bridge removal) | integration/ package cleanup |
| AP-08 (Credential namespace fix) | AP-10, AP-06 legacy deletions |
| AP-10 (GoogleConnector) | GmailAssistantSkill, CalendarEventsSkill, DriveSearchSkill |
| AP-11/16/40 (Scheduled jobs) | Bounded audit log, bounded context cache, reliable sandbox cleanup |
| AP-12 (RuntimeProfiler UI) | AP-13 (flow backpressure surfaces in Profiler tab) |
| AP-18 (Async summarizer) | applicationScope available for AP-22 |
| AP-20 (PreferenceCoordinator) | AP-49 (voice cloud sync), complete "Reset to Defaults" |
| AP-C03 (ModalBottomSheet panel) | AP-C04 (conditional display), AP-C06 (live execution) |
| AP-29 (PlannerAdaptation re-enable) | AP-30 (graph-native engine baseline data) |
| AP-51 Option C (embedding bound) | AP-51 Option A (full KNN index) |

---

## COMPLETE ACTIVATION DEFINITION OF DONE

The Activation Phase is complete when ALL of the following are verified:

### Security (Target: 75+ from current 35)
- [ ] AP-01: LLM cert pinning live for all 3 providers
- [ ] AP-02: SQLCipher encryption; ADB pull test passes
- [ ] AP-04: Zero `SecureStorage(context)` outside ServiceLocator
- [ ] AP-05: Biometric gates all 3 high-risk operations
- [ ] AP-09: Encoding bypass blocked in PermissionGovernanceLayer
- [ ] AP-21: WorldRiskProvider wired to PermissionGovernanceLayer

### Navigation (Target: Zero dead routes)
- [ ] AP-03: ArtifactPreviewScreen reachable (ArtifactCard.onClick wired)
- [ ] AP-07: AIModelsSettingsScreen reachable (SettingsScreen nav item added)
- [ ] AP-24: TemplatesScreen reachable (ChatScreen plus-menu)
- [ ] AP-25: AboutScreen + AppInfoScreen reachable (SettingsScreen + AboutScreen links)

### Connectors (Target: All 15 connectors active)
- [ ] AP-08: IntegrationsViewModel writes to ConnectorAuthManager namespace
- [ ] AP-10: GoogleConnector registered; 3 broken skills now work
- [ ] AP-06: IntegrationConnectorAdapter deleted; IntegrationManager deleted
- [ ] AP-19: N8nConnector registered with configurable webhook URL

### Memory & AI
- [ ] AP-18: No UI freeze at 200-message boundary (async summarizer)
- [ ] AP-22: User facts auto-extracted from assistant messages
- [ ] AP-51: RAG retrieval ≤ 250ms at 10,000 stored embeddings

### UX (Target: 90+ from current 68)
- [ ] AP-C07: Thinking animation (3-dot) appears pre-first-token
- [ ] AP-C08: Context reset snackbars removed from production path
- [ ] AP-C01: Input bar adapts dynamically (4 modes)
- [ ] AP-C02: Attachment thumbnails shown before send
- [ ] AP-C03: Agent panel relocated to ModalBottomSheet (non-blocking)
- [ ] AP-C04: Panel auto-expands only for 3+ step plans
- [ ] AP-C05: Daily credits counter visible in ChatScreen
- [ ] AP-C06: Planning panel reflects live step execution state
- [ ] AP-23: Export/import chat history accessible from Settings

### Observability
- [ ] AP-12: RuntimeProfiler tab in DeveloperCenterScreen
- [ ] AP-13: FlowPressureMonitor wraps AgentActivityBus + ExecutionStatusBus
- [ ] AP-11/16/40: 3 scheduled jobs active (sandbox reaper, audit pruner, cache pruner)

### Code Quality
- [ ] AP-14: 11 dead agent/decision + multiagent files deleted
- [ ] AP-15: 7 dead planning chain files deleted
- [ ] AP-52: ToolRegistry/ToolScanner either wired (Option A) or deleted (Option B)
- [ ] AP-47: DebugOverlay gated in production builds
- [ ] AP-28: SpeculativeManager acceptance rate adaptive

### Bug Fixes
- [ ] B-01: RetentionManager uses `enqueueUniqueWork`
- [ ] B-02: SessionManager has exponential backoff
- [ ] B-03: VoskEngine rapid start/stop guard
- [ ] B-05: DefaultAssistantManager role-loss receiver registered
- [ ] B-06: NotionMcpConnector caches token (5-min TTL)
- [ ] B-07: Custom endpoints protected from stale migration
- [ ] B-08: DataDeletionCoordinator forces token refresh before delete
- [ ] B-09: ExperimentManager is @Synchronized
- [ ] B-11: OrchestratorCrashReporter goal text truncated to 20 chars

### Production Score Targets
- [ ] Overall readiness: ≥ 85 / 100 (from current 61)
- [ ] Security posture: ≥ 75 / 100 (from current 35)
- [ ] UX completeness: ≥ 90 / 100 (from current 68)

---

*AIRI Activation Plan — Part 3 complete.*
*Items covered: AP-26 through AP-52, Bug Fix Batch (B-01 through B-11), Wave 5 scaffolds (AP-30 through AP-35)*
*Total activation items across all 3 parts: 50 activation features + 9 Chat UX + 18 bug/gap fixes + 2 scalability/orphan items = 79 items*
