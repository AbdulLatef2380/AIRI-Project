# AIRI ACTIVATION PLAN — PART 1
## Security Baseline + Critical Infrastructure
**Wave 1 items | P0–P1 | Days 1–7**

> Build directly on AIRI_ACTIVATION_INVENTORY, AIRI_ACTIVATION_MAP, and AIRI_EXECUTION_BLUEPRINT.
> This document is the Activation Layer — not a repeat of those documents.
> A feature is Activated only when every layer from Architecture → Testing → Legacy Removal is complete.

---

## AP-01 — LLM CERTIFICATE PINNING

### Current State
**Status:** Stub. Flag disabled. Placeholder hashes only.

**Why Not Active:**
`LlmCertPins.PINNING_ENABLED = false` — the flag gates the only branch where `CertificatePinner` is added to `OkHttpClient`. Three placeholder hashes (`"AA=="`, `"BB=="`, `"CC=="`) exist but are not real SPKI SHA-256 fingerprints. The `if (PINNING_ENABLED)` block inside `OpenAiProvider.defaultHttpClient()` is permanently skipped. The `OkHttpClient` that executes all LLM API traffic has zero certificate validation.

### Activation Path
```
DevOps: Extract real SPKI hashes (openssl commands)
    ↓
connector/api/LlmCertPins.kt — replace hashes + set flag = true
    ↓
OpenAiProvider, AnthropicProvider, GeminiProvider — verify each reads the flag
    ↓
Integration test: mitmproxy MUST reject; direct calls MUST succeed
    ↓
DeveloperCenterScreen — expose pin status in Connectors tab
    ↓
docs/RUNBOOK.md — certificate rotation procedure
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `connector/api/LlmCertPins.kt` | Replace `"AA=="`, `"BB=="`, `"CC=="` with real SPKI SHA-256 hashes; add backup hash per provider; set `PINNING_ENABLED = true` |
| `ai/remote/AnthropicProvider.kt` | Verify `if (LlmCertPins.PINNING_ENABLED)` gate identical to `OpenAiProvider` |
| `ai/remote/GeminiProvider.kt` | Same as above |
| `ui/DeveloperCenterScreen.kt` | Add pin status row to Connectors tab |
| `docs/RUNBOOK.md` | CREATE — certificate rotation procedure |

### Exact Classes to Modify
`LlmCertPins`, `OpenAiProvider`, `AnthropicProvider`, `GeminiProvider`, `DeveloperCenterScreen`

### Exact Methods to Modify
- `OpenAiProvider.defaultHttpClient()` — verify `builder.certificatePinner(...)` is inside the gate
- `AnthropicProvider.defaultHttpClient()` — same gate verification
- `GeminiProvider.defaultHttpClient()` — same gate verification

### Dependency Activation Graph
```
AP-01 Activated
    → All LLM API traffic (ChatViewModel.sendMessage → AgentLoop → HybridOrchestrator → RemoteLlmConnector) is MitM-protected
    → Production release unblocked for LLM security posture
    → No downstream feature dependencies — this is a security perimeter, not a feature gate
```

### Ripple Effect
**2 files** require verification (AnthropicProvider, GeminiProvider — gate pattern must match OpenAiProvider).
**1 file** primary change (LlmCertPins.kt).
**1 file** UI display (DeveloperCenterScreen.kt).
**1 file** created (RUNBOOK.md).
**Total: 5 files affected.**

### Migration Strategy
```
Old: PINNING_ENABLED = false, placeholder hashes
    ↓ (no bridge needed — flag flip is atomic)
New: PINNING_ENABLED = true, real SPKI hashes
    ↓
Validation: mitmproxy intercept test; direct API test
    ↓
No old code to delete — structure was already correct
```

### Legacy Removal Strategy
Nothing to delete. The scaffold was correct; only the flag value and hash values were wrong.

### Wiring Plan
The OkHttpClient wiring path already exists inside the `if (PINNING_ENABLED)` block. Activation is purely:
1. Hash values → real SPKI fingerprints
2. Flag → `true`

No DI changes. No new classes. No navigation changes.

### UI Activation
Add a status row in `DeveloperCenterScreen` → Connectors tab:
```kotlin
ConnectorStatusRow(
    name = "LLM Certificate Pinning",
    status = if (LlmCertPins.PINNING_ENABLED) "Active" else "DISABLED",
    statusColor = if (LlmCertPins.PINNING_ENABLED) Color.Green else Color.Red
)
```

### Repository Activation
Not applicable — no repository layer involved.

### Service Activation
Not applicable — OkHttpClient is configured at provider construction time.

### Testing Strategy
```
Integration tests:
1. mitmproxy intercept of api.openai.com:443 → SSLPeerUnverifiedException — REQUIRED
2. mitmproxy intercept of api.anthropic.com:443 → SSLPeerUnverifiedException — REQUIRED
3. mitmproxy intercept of generativelanguage.googleapis.com:443 → SSLPeerUnverifiedException — REQUIRED
4. Direct call to all 3 providers → successful response — REQUIRED
5. At least 2 hashes per provider present (backup for rotation) — REQUIRED

Regression:
6. Existing chat flow with all 3 providers: no regressions in latency or success rate
```

### Rollback Strategy
Set `PINNING_ENABLED = false` in `LlmCertPins.kt`. Instant rollback, no data loss risk. Certificate changes do not affect stored data.

### Definition of Done
- [ ] `PINNING_ENABLED = true` committed
- [ ] Real SPKI SHA-256 hashes in place for OpenAI, Anthropic, Gemini (≥ 2 per host)
- [ ] mitmproxy intercept test: all 3 providers REJECT
- [ ] Direct API test: all 3 providers SUCCEED
- [ ] AnthropicProvider and GeminiProvider gate-pattern verified identical to OpenAiProvider
- [ ] DeveloperCenterScreen Connectors tab shows pin status
- [ ] RUNBOOK.md has certificate rotation procedure

---

## AP-02 — SQLITE DATABASE ENCRYPTION (SQLCipher)

### Current State
**Status:** Scaffold. Flag disabled. Dependency present but not wired.

**Why Not Active:**
`AiriDatabase.ENCRYPTION_ENABLED = false`. `SQLCipher` is in `build.gradle` but `SupportFactory(key)` is not passed to `Room.databaseBuilder()`. No encryption key generation exists. No migration helper exists. All 9 Room tables (conversations, embeddings, audit logs, workspace artifacts, behavior stats, context cache, usage stats, sessions, message embeddings) are stored in plaintext SQLite at `databases/airi_database.db` — readable via `adb pull` on debug builds and on any rooted device.

**Dependency:** AP-04 (SecureStorage Singleton Consolidation) must complete first. The encryption key must be generated and retrieved via `ServiceLocator.secureStorage`, not a direct `SecureStorage(context)` instantiation.

### Activation Path
```
AP-04 completes (SecureStorage singleton available)
    ↓
CREATE memory/AiriDatabaseMigrationHelper.kt (~200 lines)
    ↓
memory/AiriDatabase.kt — call migrateIfNeeded() + wire SupportFactory(key)
    ↓
Set ENCRYPTION_ENABLED = true
    ↓
AuditRepository.log("DB_ENCRYPTED", ...) on successful migration
    ↓
Device test: adb pull → binary unreadable
    ↓
Upgrade path test: existing messages present after migration
    ↓
Production Ready
```

### Exact Files to Modify / Create
| File | Change |
|:---|:---|
| `memory/AiriDatabaseMigrationHelper.kt` | CREATE — ~200 lines. ATTACH → export → rename logic |
| `memory/AiriDatabase.kt` | Call `migrateIfNeeded()`; add `SupportFactory(key)` to `Room.databaseBuilder()`; set `ENCRYPTION_ENABLED = true` |

### Exact Classes to Modify / Create
- `AiriDatabaseMigrationHelper` (new) — implements plaintext-to-encrypted ATTACH export
- `AiriDatabase` — wires `SupportFactory`, calls migration helper, sets flag

### Exact Methods to Modify
- `AiriDatabase.create()` or `ServiceLocator.init()` — add key generation + migration helper call + `SupportFactory` wire
- `AiriDatabaseMigrationHelper.migrateIfNeeded(context, encKey)` — new method (CREATE)

### Full Implementation of `AiriDatabaseMigrationHelper.kt`
```kotlin
object AiriDatabaseMigrationHelper {
    private const val DB_NAME = "airi_database.db"

    fun migrateIfNeeded(context: Context, encKey: String) {
        val dbPath = context.getDatabasePath(DB_NAME).absolutePath
        val encPath = "$dbPath.encrypted"
        val plaintextFile = File(dbPath)

        // Only run if a plaintext database exists
        if (!plaintextFile.exists()) return
        // Check if it's already encrypted (SQLCipher file starts with "SQLite" magic for plaintext)
        val magic = plaintextFile.readBytes().take(6).toByteArray()
        if (!String(magic).startsWith("SQLite")) return // already encrypted

        try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawExecSQL("ATTACH DATABASE '$encPath' AS enc KEY '$encKey'")
                db.rawExecSQL("SELECT sqlcipher_export('enc')")
                db.rawExecSQL("DETACH DATABASE enc")
            }
            File(dbPath).delete()
            File(encPath).renameTo(File(dbPath))
        } catch (e: Exception) {
            File(encPath).delete() // clean up on failure; let next launch retry
            throw e
        }
    }
}
```

### Exact Wiring in `AiriDatabase.kt`
```kotlin
// In AiriDatabase.create() or ServiceLocator:
val encKey = ServiceLocator.secureStorage.getOrCreate("airi_db_encryption_key") {
    ByteArray(32).also { SecureRandom().nextBytes(it) }.encodeBase64()
}
AiriDatabaseMigrationHelper.migrateIfNeeded(context, encKey)

Room.databaseBuilder(context, AiriDatabase::class.java, "airi_database.db")
    .openHelperFactory(SupportFactory(encKey.toByteArray()))  // ADD THIS
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    .build()
    .also { db ->
        ServiceLocator.auditRepository.logSync("DB_ENCRYPTED", "SQLCipher migration complete")
    }
```

### Dependency Activation Graph
```
AP-04 Activated (SecureStorage singleton)
    ↓
AP-02 Activated (SQLCipher database encryption)
    ↓
All 9 Room tables now encrypted at rest:
    → ChatSession, ChatMessage, MessageEmbedding — conversation privacy secured
    → AuditLogEntity — audit trail secured
    → ArtifactEntity — workspace artifacts secured
    → BehaviorStatsEntity — behavioral data secured
    → ContextCacheEntity — context data secured
    → UsageStatEntity — usage data secured
    → Security posture score: +20 points toward 75+ target
```

### Ripple Effect
**2 files directly modified** (`AiriDatabase.kt`, new `AiriDatabaseMigrationHelper.kt`).
**0 DAO files change** — encryption is transparent at the database level; all 8 DAOs continue unchanged.
**1 ServiceLocator change** if key generation is moved there.
**Total: 2–3 files.**

### Migration Strategy
```
Old: plaintext SQLite, no SupportFactory
    ↓ AiriDatabaseMigrationHelper.migrateIfNeeded() — ATTACH export
    ↓ New: SQLCipher-encrypted DB at same path
    ↓ Validation: adb pull returns binary; all existing data present
    ↓ Delete old code: set ENCRYPTION_ENABLED = true permanently; remove flag checks
```

### Legacy Removal Strategy
- After successful production deployment with ENCRYPTION_ENABLED = true:
  - Remove the `ENCRYPTION_ENABLED` constant entirely — replace all `if (ENCRYPTION_ENABLED)` with unconditional code
  - `AiriDatabaseMigrationHelper.migrateIfNeeded()` can be removed after one full release cycle (all users will have migrated by then)
  - **When safe to delete migration helper:** After the second release post-encryption (all live users will have an encrypted DB by then)

### Wiring Plan
```
ServiceLocator.init()
    → secureStorage.getOrCreate("airi_db_encryption_key") { generateKey() }
    → AiriDatabaseMigrationHelper.migrateIfNeeded(context, key)
    → AiriDatabase.create(context, SupportFactory(key))
        → All 8 DAOs constructed normally (transparent to DAOs)
        → AuditRepository.logSync("DB_ENCRYPTED", ...)
```

### Testing Strategy
```
Device tests (required on physical device):
1. Upgrade path: device with existing chat history → launch after AP-02 → all messages present
2. adb pull databases/airi_database.db → not openable in DB Browser for SQLite → REQUIRED
3. Fresh install: encrypted DB created from first launch → adb pull → binary
4. Keystore failure simulation: SecureStorage returns null → explicit error thrown, NOT silent plaintext fallback

Unit tests:
5. AiriDatabaseMigrationHelper: mock plaintext DB → migrateIfNeeded → verify encrypted file created
6. AiriDatabaseMigrationHelper: encrypted DB present → migrateIfNeeded → no-op (idempotent)

Regression:
7. All existing Room DAO tests pass with SupportFactory in place
8. Full chat flow: send message → restart app → message persists (encrypted round-trip)
```

### Rollback Strategy
Set `ENCRYPTION_ENABLED = false` and remove `SupportFactory` line. **WARNING:** This will make an already-migrated encrypted database unreadable. Only safe to roll back before any user's database has been migrated. If rollback is needed after migration: `AiriDatabaseMigrationHelper.migrateIfNeeded()` must be run in reverse (decrypt → plaintext). Pre-plan this decryption path before deploying.

### Definition of Done
- [ ] `ENCRYPTION_ENABLED = true` committed and flag-check branches removed
- [ ] `AiriDatabaseMigrationHelper` implemented, tested, code-reviewed
- [ ] `SupportFactory(key)` wired in `Room.databaseBuilder()`
- [ ] Upgrade path test on physical device: zero data loss
- [ ] Fresh install test: encrypted from first launch
- [ ] `adb pull` test: file not readable as SQLite
- [ ] Keystore failure test: explicit error surfaced, not silent plaintext fallback
- [ ] Audit log entry written on successful migration

---

## AP-03 — ARTIFACT PREVIEW SCREEN NAVIGATION

### Current State
**Status:** Disconnected. Route registered. Screen implemented. Zero navigate() callers.

**Why Not Active:**
`ArtifactCard` composable in `WorkspaceScreen.kt` has no `onClick` handler and no `onArtifactClick` callback parameter. `AiriRoute.ARTIFACT_PREVIEW` is registered in the 44-route NavHost and `ArtifactPreviewScreen` is fully implemented with sandboxed WebView (XSS prevention), Markdown renderer, and code renderer. The entire preview workflow is unreachable because the entry point (tap on ArtifactCard) does nothing.

### Activation Path
```
ui/WorkspaceScreen.kt — ArtifactCard: add onArtifactClick callback parameter
    ↓
ui/WorkspaceScreen.kt — ArtifactCard body: add .clickable { onArtifactClick(artifact) }
    ↓
ui/WorkspaceScreen.kt — LazyColumn: pass navigate lambda to each ArtifactCard
    ↓
ArtifactPreviewScreen.kt — verify navBackStackEntry.arguments["content"] received correctly
    ↓
Manual test: tap artifact → preview opens for all 3 types (HTML, Markdown, Code)
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/WorkspaceScreen.kt` | Add `onArtifactClick` param to `ArtifactCard`; add `.clickable {}`; pass navigate lambda |

### Exact Classes to Modify
`ArtifactCard` (composable function), `WorkspaceScreen` (composable function)

### Exact Methods / Composables to Modify
```kotlin
// BEFORE:
@Composable
fun ArtifactCard(
    artifact: WorkspaceArtifact,
    modifier: Modifier = Modifier
)

// AFTER:
@Composable
fun ArtifactCard(
    artifact: WorkspaceArtifact,
    onArtifactClick: (WorkspaceArtifact) -> Unit,  // ADD
    modifier: Modifier = Modifier
)

// Inside ArtifactCard body, add to root container Modifier:
.clickable { onArtifactClick(artifact) }

// In WorkspaceScreen, wherever ArtifactCard is called in LazyColumn:
ArtifactCard(
    artifact = artifact,
    onArtifactClick = { selected ->
        onNavigate("${AiriRoute.ARTIFACT_PREVIEW}/${Uri.encode(selected.content)}")
    }
)
```

### Dependency Activation Graph
```
AP-03 Activated
    ↓
ArtifactPreviewScreen becomes reachable
    ↓
Sandboxed WebView (CSP: javaScriptEnabled=false, no file access) now active for HTML artifacts
    ↓
BidiAwareMarkdownRenderer now active for Markdown artifacts
    ↓
Monospace syntax block now active for Code artifacts
    ↓
WorkspaceScreen is now fully functional (both list view AND preview view)
    ↓
Agent-generated artifacts are now usable, not just visible by name
```

### Ripple Effect
**1 file** (`WorkspaceScreen.kt`). ~10 lines of change. Zero other files affected.

### Migration Strategy
No migration needed. Pure additive wiring of an existing route.

### Legacy Removal Strategy
Nothing to remove. The missing link was the only problem.

### Wiring Plan
```
WorkspaceScreen composable receives: onNavigate: (String) -> Unit
    ↓
ArtifactCard composable receives: onArtifactClick: (WorkspaceArtifact) -> Unit
    ↓
.clickable {} inside ArtifactCard body
    ↓
onNavigate("${AiriRoute.ARTIFACT_PREVIEW}/${Uri.encode(artifact.content)}")
    ↓
NavHost route: AiriRoute.ARTIFACT_PREVIEW + "/{content}"
    ↓
ArtifactPreviewScreen(content = navBackStackEntry.arguments?.getString("content"))
```

### UI Activation
The `ArtifactPreviewScreen` is already fully built. The only UI change is adding `Modifier.clickable` to the card. The screen becomes reachable through normal tap interaction — no new menu entries, buttons, or navigation entries required.

### Testing Strategy
```
Manual tests (all required):
1. Generate an HTML artifact via agent → tap ArtifactCard → ArtifactPreviewScreen opens → renders in sandboxed WebView
2. Generate a Markdown artifact → tap → renders via BidiAwareMarkdownRenderer
3. Generate a Code artifact → tap → renders in monospace block with syntax highlighting
4. Back navigation from ArtifactPreviewScreen → returns to WorkspaceScreen
5. CSP verification: inject <script> in HTML artifact → no execution

Unit test:
6. ArtifactCard with onArtifactClick mock → simulate click → mock called with correct artifact
```

### Rollback Strategy
Remove the `.clickable {}` modifier and the `onArtifactClick` parameter. Instant, zero risk.

### Definition of Done
- [ ] `ArtifactCard.onArtifactClick` callback wired
- [ ] `AiriRoute.ARTIFACT_PREVIEW` has ≥ 1 `navigate()` caller
- [ ] HTML artifact opens in sandboxed WebView (`javaScriptEnabled = false`, no file access)
- [ ] Markdown artifact opens in BidiAwareMarkdownRenderer
- [ ] Code artifact opens in monospace block
- [ ] Back navigation returns to WorkspaceScreen
- [ ] Zero dead artifact routes remain

---

## AP-04 — SECUREST STORAGE SINGLETON CONSOLIDATION

### Current State
**Status:** Partially Active — Split-Brain. 7 direct instantiations bypass the singleton.

**Why Not Active:**
7 classes construct `SecureStorage(context)` directly instead of using `ServiceLocator.secureStorage`. When Android Keystore fails, each instance creates its own in-memory `ConcurrentHashMap`. Data written by `PorcupineEngine`'s instance is invisible to `ConnectorBootstrap`'s instance. This causes silent data inconsistency during Keystore failure.

### Activation Path
```
For each of the 7 files:
    Replace SecureStorage(context) → ServiceLocator.secureStorage
    ↓
Verify ServiceLocator.init() completes before any of the 7 classes construct
    ↓
Static analysis: grep -r "SecureStorage(context)" --include="*.kt" → zero matches
    ↓
Keystore failure simulation test: data written by one class readable by another
    ↓
AP-02 (database encryption) can now safely proceed
    ↓
AP-05 (BiometricGatekeeper) can now safely proceed
    ↓
Production Ready
```

### Exact Files to Modify (all 7 — one-line change each)
| File | Old | New |
|:---|:---|:---|
| `voice/PorcupineEngine.kt` | `SecureStorage(context)` | `ServiceLocator.secureStorage` |
| `agent/AgentWorker.kt` | `SecureStorage(context)` | `ServiceLocator.secureStorage` |
| `connector/ConnectorBootstrap.kt` | `SecureStorage(context)` | `ServiceLocator.secureStorage` |
| `ai/skills/SkillRegistry.kt` | `SecureStorage(context)` | `ServiceLocator.secureStorage` |
| `ui/viewmodel/IntegrationsViewModel.kt` | `SecureStorage(context)` (line 58) | `ServiceLocator.secureStorage` |
| `ExecModePreferences.kt` | direct instantiation | `ServiceLocator.secureStorage` |
| `domain/growth/ReferralManager.kt` | direct instantiation | `ServiceLocator.secureStorage` |

### Exact Classes to Modify
`PorcupineEngine`, `AgentWorker`, `ConnectorBootstrap`, `SkillRegistry`, `IntegrationsViewModel`, `ExecModePreferences`, `ReferralManager`

### Exact Methods to Modify
- `PorcupineEngine.init()` or constructor — where `SecureStorage(context)` is assigned
- `AgentWorker.doWork()` — where `SecureStorage(context)` is assigned
- `ConnectorBootstrap` — parameter or init block where `SecureStorage(context)` is assigned
- `SkillRegistry` constructor — where `SecureStorage(context)` is assigned
- `IntegrationsViewModel` line 58 — `val secureStorage = SecureStorage(context)` → `val secureStorage = ServiceLocator.secureStorage`
- `ExecModePreferences` — construction site
- `ReferralManager` — construction site

### Dependency Activation Graph
```
AP-04 Activated (singleton enforced)
    ↓
AP-02 (SQLCipher) can now use shared SecureStorage for key generation
    ↓
AP-05 (BiometricGatekeeper) uses consistent secure storage
    ↓
AP-06 (Legacy bridge removal) — ConnectorBootstrap uses correct singleton
    ↓
AP-08 (IntegrationsViewModel credential fix) — base for credential namespace unification
    ↓
A-10 (GoogleConnector) — credential lookup works correctly
    ↓
All connector auth flows are Keystore-failure-safe
```

### Ripple Effect
**7 files** modified. Each change is exactly 1 line. No method signatures change. No new classes. No DI graph changes.

### Migration Strategy
```
Old: SecureStorage(context) — 7 separate instances, 7 separate in-memory maps on Keystore failure
    ↓ (no bridge needed — direct substitution)
New: ServiceLocator.secureStorage — 1 shared instance, 1 shared in-memory map
    ↓
Validation: grep returns zero matches; Keystore failure test passes
    ↓
Delete old: nothing to delete — the SecureStorage class itself is retained as the singleton implementation
```

### Legacy Removal Strategy
No classes deleted. The `SecureStorage` class becomes correctly used through `ServiceLocator` only. The 7 construction sites are the legacy — they are removed by the substitution itself.

### Wiring Plan
```
ServiceLocator.init() (called in AIRIApplication.onCreate())
    → creates SecureStorage singleton once
    → stores as ServiceLocator.secureStorage

All 7 classes → ServiceLocator.secureStorage (shared reference)

ServiceLocator.init() MUST complete before any of these 7 classes are constructed:
    Verify call order in AIRIApplication.onCreate():
    1. ServiceLocator.init(context) ← must be first
    2. Then: PorcupineEngine, AgentWorker, ConnectorBootstrap, etc.
```

### Service Activation
`ServiceLocator.init()` must be confirmed as the first call in `AIRIApplication.onCreate()` before any of the 7 affected classes are instantiated. Check the call order:
```kotlin
// AIRIApplication.onCreate():
ServiceLocator.init(applicationContext)  // MUST be first
// ... then all other initializations
```

### Testing Strategy
```
Static analysis (automated, run in CI):
1. grep -r "SecureStorage(context)\|SecureStorage(applicationContext)" --include="*.kt" → zero matches

Unit test:
2. Simulate Keystore failure (mock SecureStorage to use in-memory map)
   → Write key "test_key" via PorcupineEngine path
   → Read "test_key" via ConnectorBootstrap path
   → Value MUST be found (shared map)
   [Before AP-04: this test fails. After: it passes.]

Integration test:
3. Full app launch with Keystore mocked as failed
   → Voice wake word key stored
   → Connector auth retrieved correctly
   → No silent failures
```

### Rollback Strategy
Revert the 7 one-line changes. No data loss risk. The `SecureStorage` class itself is unchanged.

### Definition of Done
- [ ] All 7 direct `SecureStorage(context)` instantiations replaced with `ServiceLocator.secureStorage`
- [ ] `grep -r "SecureStorage(context)"` returns zero matches
- [ ] Keystore failure simulation test: data written by one class is readable by another
- [ ] `ServiceLocator.init()` confirmed as first call in `AIRIApplication.onCreate()`
- [ ] Build succeeds; all tests pass

---

## AP-05 — BIOMETRIC GATEKEEPER WIRING

### Current State
**Status:** Orphaned. Fully implemented. Zero call sites anywhere in the codebase.

**Why Not Active:**
`BiometricGatekeeper.authenticate()` is implemented and correct. It is simply never called. Three high-risk operations — account deletion (8-step irreversible wipe), accessibility service grant (full device control), and FULL_AGENT mode activation (fully autonomous agent) — all proceed without any biometric confirmation.

**Architecture constraint:** `BiometricGatekeeper.authenticate()` requires a `FragmentActivity`. ViewModels must never hold Activity references. Operations 1 and 2 are triggered from Composables (have `LocalContext.current`). Operation 3 must use a ViewModel `SharedFlow` event pattern.

**Dependency:** AP-04 must complete first.

### Activation Path
```
AP-04 completes (SecureStorage singleton stable)
    ↓
Call site 1: ui/PrivacyDataSettingsScreen.kt — gate before dataViewModel.deleteAccount()
    ↓
Call site 2: ui/PermissionsScreen.kt — gate before startActivity(ACTION_ACCESSIBILITY_SETTINGS)
    ↓
Call site 3: ui/viewmodel/ChatViewModel.kt — add BiometricRequest sealed class + SharedFlow + requestSetExecutionMode() + onBiometricSuccess()
    ↓
Call site 3: ui/ChatScreen.kt — add LaunchedEffect to collect biometricRequest + call BiometricGatekeeper
    ↓
Handle BIOMETRIC_ERROR_NONE_ENROLLED at all 3 sites — show Settings prompt dialog
    ↓
Testing: all 3 operations blocked on cancellation; device PIN fallback works
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/PrivacyDataSettingsScreen.kt` | Add biometric gate before `dataViewModel.deleteAccount()` call (~10 lines) |
| `ui/PermissionsScreen.kt` | Add biometric gate before accessibility Intent launch (~8 lines) |
| `ui/viewmodel/ChatViewModel.kt` | Add `BiometricRequest` sealed class, `biometricRequest: SharedFlow`, `requestSetExecutionMode()`, `onBiometricSuccess()` (~20 lines) |
| `ui/ChatScreen.kt` | Add `LaunchedEffect` to collect `biometricRequest` and call `BiometricGatekeeper.authenticate()` (~15 lines) |

### Exact Classes to Modify
`PrivacyDataSettingsScreen`, `PermissionsScreen`, `ChatViewModel`, `ChatScreen`

### Exact Methods to Modify / Add

**`ChatViewModel`** — add:
```kotlin
sealed class BiometricRequest {
    object FullAgentEnable : BiometricRequest()
}
private val _biometricRequest = MutableSharedFlow<BiometricRequest>()
val biometricRequest: SharedFlow<BiometricRequest> = _biometricRequest.asSharedFlow()

fun requestSetExecutionMode(newMode: ExecMode) {
    if (newMode == ExecMode.FULL_AGENT) {
        viewModelScope.launch { _biometricRequest.emit(BiometricRequest.FullAgentEnable) }
    } else {
        setExecutionModeInternal(newMode)
    }
}

fun onBiometricSuccess(request: BiometricRequest) {
    when (request) {
        is BiometricRequest.FullAgentEnable -> setExecutionModeInternal(ExecMode.FULL_AGENT)
    }
}
```

**`ChatScreen`** — add LaunchedEffect:
```kotlin
val activity = LocalContext.current as FragmentActivity
LaunchedEffect(Unit) {
    chatViewModel.biometricRequest.collect { request ->
        val biometricManager = BiometricManager.from(activity)
        if (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            // Show dialog: "Add fingerprint or face unlock in Settings"
            return@collect
        }
        val passed = BiometricGatekeeper.authenticate(
            activity = activity,
            reason = "Enable fully autonomous agent mode"
        )
        if (passed) chatViewModel.onBiometricSuccess(request)
    }
}
```

**`PrivacyDataSettingsScreen`** — gate before deletion:
```kotlin
val ctx = LocalContext.current as FragmentActivity
// In the delete account button onClick:
val passed = BiometricGatekeeper.authenticate(
    activity = ctx,
    reason = "Confirm account deletion — this cannot be undone"
)
if (!passed) return@onClick
dataViewModel.deleteAccount()
```

**`PermissionsScreen`** — gate before accessibility Intent:
```kotlin
val ctx = LocalContext.current as FragmentActivity
// In the accessibility grant button onClick:
val passed = BiometricGatekeeper.authenticate(
    activity = ctx,
    reason = "Enable AIRI Accessibility Service"
)
if (!passed) return@onClick
ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
```

### Dependency Activation Graph
```
AP-05 Activated (3 high-risk operations gated)
    ↓
Account deletion: irreversible wipe is now biometric-confirmed
    ↓
Accessibility service: full device control is now biometric-confirmed
    ↓
FULL_AGENT mode: autonomous execution is now biometric-confirmed
    ↓
A-32 (VNC/Remote Desktop) — FULL_AGENT gate is foundation for future remote control consent
    ↓
Security posture: +15 points toward 75+ target
```

### Ripple Effect
**4 files** modified. No new files created. No DI changes. No navigation changes. No database changes.

### Migration Strategy
No migration. Purely additive — no existing code is replaced, only wrapped with a pre-condition.

### Legacy Removal Strategy
Nothing to remove.

### Wiring Plan
```
Call site 1 (account deletion):
    PrivacyDataSettingsScreen (Composable) — LocalContext.current as FragmentActivity
    → BiometricGatekeeper.authenticate(activity, reason)
    → if passed: dataViewModel.deleteAccount()
    → if failed/cancelled: no-op

Call site 2 (accessibility grant):
    PermissionsScreen (Composable) — LocalContext.current as FragmentActivity
    → BiometricGatekeeper.authenticate(activity, reason)
    → if passed: startActivity(ACTION_ACCESSIBILITY_SETTINGS)
    → if failed/cancelled: no-op

Call site 3 (FULL_AGENT mode):
    ChatViewModel.requestSetExecutionMode(FULL_AGENT)
    → _biometricRequest.emit(BiometricRequest.FullAgentEnable)
    → ChatScreen LaunchedEffect collects event
    → BiometricGatekeeper.authenticate(activity, reason)
    → if passed: chatViewModel.onBiometricSuccess(request)
    → setExecutionModeInternal(FULL_AGENT)
```

### Testing Strategy
```
Manual device tests:
1. Tap "Delete Account" → biometric prompt appears → cancel → account NOT deleted
2. Tap "Delete Account" → biometric success → account deleted (8-step wipe proceeds)
3. Tap "Grant Accessibility Service" → biometric prompt appears → cancel → Intent NOT launched
4. Tap "Grant Accessibility Service" → biometric success → accessibility settings opened
5. Select FULL_AGENT mode → biometric prompt appears → cancel → mode NOT changed
6. Select FULL_AGENT mode → biometric success → mode changes to FULL_AGENT

No-biometric-enrolled device tests:
7. Tap any of 3 operations → dialog: "Add fingerprint or face unlock in Settings" shown
8. No crash; no operation proceeds

Unit tests:
9. ChatViewModel.requestSetExecutionMode(FULL_AGENT) → biometricRequest emits FullAgentEnable
10. ChatViewModel.onBiometricSuccess(FullAgentEnable) → setExecutionModeInternal(FULL_AGENT) called
11. ChatViewModel.requestSetExecutionMode(LOCAL) → no SharedFlow emission; setExecutionModeInternal called directly
```

### Rollback Strategy
Remove the biometric gate calls at the 3 call sites. No data changes; no database impact.

### Definition of Done
- [ ] `BiometricGatekeeper.authenticate()` called before account deletion in `PrivacyDataSettingsScreen`
- [ ] `BiometricGatekeeper.authenticate()` called before accessibility grant in `PermissionsScreen`
- [ ] `ChatViewModel.biometricRequest: SharedFlow` wired; `ChatScreen` collects and gates FULL_AGENT activation
- [ ] No `FragmentActivity` reference held in `ChatViewModel` (memory leak violation)
- [ ] Cancellation blocks all 3 operations
- [ ] No-biometric-enrolled case shows Settings prompt (no crash)
- [ ] Device PIN fallback works on hardware-biometric-absent devices

---

## AP-06 — LEGACY INTEGRATION BRIDGE REMOVAL

### Current State
**Status:** Legacy still used. Silent failure in production. Phantom "connected" states.

**Why Not Active (for removal):**
`ConnectorBootstrap.kt` fallback: when `secureStorage` is null, it wraps `TelegramIntegration()` in `IntegrationConnectorAdapter`. The adapter only handles `"status"` action — all real operations return `ConnectorResult.Failure("Legacy adapter: unsupported action")`. Users see a "connected" connector that silently fails every command. `IntegrationManager.kt` is fully dead (zero callers outside its own package, marked `@Deprecated`).

**Dependency:** AP-04 must complete first (so the null-storage fallback no longer triggers). AP-08 must complete before `GithubIntegration`, `TelegramIntegration`, and `NotionIntegration` can be deleted (credentials must be migrated first).

### Activation Path
```
AP-04 completes (SecureStorage singleton — null case no longer occurs in normal operation)
    ↓
Delete integration/IntegrationManager.kt (zero callers — confirmed)
    ↓
connector/ConnectorBootstrap.kt — remove IntegrationConnectorAdapter fallback
    ↓
Replace fallback with: connectorRegistry.markUnavailable(connectorId, "Secure storage unavailable")
    ↓
connector/ConnectorHealthMonitor.kt — ensure UNAVAILABLE status is reported
    ↓
Delete connector/legacy/IntegrationConnectorAdapter.kt (verify zero callers first)
    ↓
AP-08 completes (credential migration)
    ↓
Delete integration/GithubIntegration.kt, TelegramIntegration.kt, NotionIntegration.kt
    ↓
Production Ready
```

### Exact Files to Modify / Delete
| File | Action |
|:---|:---|
| `integration/IntegrationManager.kt` | DELETE (zero callers confirmed) |
| `connector/ConnectorBootstrap.kt` | Remove `IntegrationConnectorAdapter` fallback; add `markUnavailable()` |
| `connector/legacy/IntegrationConnectorAdapter.kt` | DELETE (after zero-callers verification) |
| `connector/ConnectorHealthMonitor.kt` | Verify UNAVAILABLE status propagation |
| `ui/DeveloperCenterScreen.kt` | Ensure UNAVAILABLE displays as "Secure storage error" not "Connected" |
| `integration/GithubIntegration.kt` | DELETE (after AP-08 migration complete) |
| `integration/TelegramIntegration.kt` | DELETE (after AP-08 migration complete) |
| `integration/NotionIntegration.kt` | DELETE (after AP-08 migration complete) |

### Exact Change in `ConnectorBootstrap.kt`
```kotlin
// BEFORE:
val storage = secureStorage ?: IntegrationConnectorAdapter(TelegramIntegration())

// AFTER:
val storage = secureStorage ?: run {
    connectorRegistry.markUnavailable("telegram", "Secure storage unavailable")
    return
}
```

### Dependency Activation Graph
```
AP-04 completes
    ↓
AP-06 removes phantom connected states
    ↓
ConnectorHealthMonitor now reports UNAVAILABLE (not phantom CONNECTED) on Keystore failure
    ↓
AP-08 credential migration completes
    ↓
GithubIntegration, TelegramIntegration, NotionIntegration safely deleted
    ↓
integration/ package fully cleaned — legacy bridge entirely eliminated
    ↓
connector/legacy/ package deleted entirely
```

### Ripple Effect
**8 files total**: 4 deleted, 3 modified, 1 verified. The `integration/` package may become empty after deletions — if so, remove the empty package directory.

### Migration Strategy
```
Old: IntegrationConnectorAdapter (status-only, silent failure)
    ↓ AP-08: migrate credentials to ConnectorAuthManager namespace
    ↓ AP-06: replace adapter fallback with explicit UNAVAILABLE state
    ↓ Verify: ConnectorRegistry shows UNAVAILABLE (not CONNECTED) when storage fails
    ↓ Delete: IntegrationConnectorAdapter.kt, IntegrationManager.kt
    ↓ Delete (post AP-08): GithubIntegration.kt, TelegramIntegration.kt, NotionIntegration.kt
```

### Legacy Removal Strategy
- **Week 1 (with AP-06):** Delete `IntegrationManager.kt`, `IntegrationConnectorAdapter.kt`
- **Week 2 (after AP-08 completes):** Delete `GithubIntegration.kt`, `TelegramIntegration.kt`, `NotionIntegration.kt`
- **Safe-to-delete verification for each:** `grep -r "ClassName" --include="*.kt"` returns zero matches outside the file itself
- **After deletion:** Build must succeed with zero compile errors

### Testing Strategy
```
1. Simulate Keystore failure (mock SecureStorage.init() to throw)
   → Open ConnectorsScreen
   → Affected connectors show "Secure storage error" status (UNAVAILABLE)
   → No connector shows "Connected" status

2. Grep confirms zero callers of IntegrationManager outside its own file → THEN delete
3. Grep confirms zero callers of IntegrationConnectorAdapter → THEN delete
4. After deletion: full build with zero compile errors
5. After AP-08: grep confirms zero callers of GithubIntegration/TelegramIntegration/NotionIntegration → THEN delete
```

### Rollback Strategy
Restore deleted files from git. The `IntegrationConnectorAdapter` fallback logic can be restored in `ConnectorBootstrap` from git. No data changes.

### Definition of Done
- [ ] `IntegrationManager.kt` deleted
- [ ] `IntegrationConnectorAdapter.kt` deleted
- [ ] `ConnectorBootstrap.kt` fallback removed; `markUnavailable()` used instead
- [ ] No phantom "CONNECTED" connector state on Keystore failure
- [ ] DeveloperCenterScreen shows "Secure storage error" for affected connectors when storage fails
- [ ] (Post AP-08) `GithubIntegration.kt`, `TelegramIntegration.kt`, `NotionIntegration.kt` deleted
- [ ] Full build: zero compile errors after each deletion

---

## AP-07 — AI MODELS SETTINGS SCREEN NAVIGATION

### Current State
**Status:** Disconnected. Route registered. Screen implemented. Wrong nav item target.

**Why Not Active:**
`SettingsScreen.kt` line 214 calls `onNavigate(AiriRoute.MODEL_LIBRARY)` — the wrong route. `AiriRoute.SETTINGS_AI_MODELS` is registered in NavHost and the `AIModelsSettingsScreen` is fully implemented with controls for ExecutionMode (LOCAL/CLOUD/HYBRID), PrivacyLevel, InternetPermission, OfflineFallback, and PreferredProvider. Users cannot reach these 5 critical controls through any navigation path.

### Activation Path
```
ui/SettingsScreen.kt — add SettingsNavItem for AiriRoute.SETTINGS_AI_MODELS
    ↓
AiriRoute.SETTINGS_AI_MODELS now has ≥ 1 navigate() caller
    ↓
AIModelsSettingsScreen reachable from Settings
    ↓
ExecutionMode, PrivacyLevel, InternetPermission, OfflineFallback, PreferredProvider — all user-accessible
    ↓
Integration test: change persists in airi_exec_prefs_secure; ChatViewModel picks up on next send
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/SettingsScreen.kt` | Add `SettingsNavItem` for `SETTINGS_AI_MODELS` (~5 lines) |

### Exact Change
```kotlin
// Add in SettingsScreen.kt, after or adjacent to the MODEL_LIBRARY nav item:
SettingsNavItem(
    title = "AI Execution Settings",
    subtitle = "Execution mode, privacy level, provider",
    icon = Icons.Default.Psychology,
    onClick = { onNavigate(AiriRoute.SETTINGS_AI_MODELS) }
)
```

### Dependency Activation Graph
```
AP-07 Activated
    ↓
AIModelsSettingsScreen reachable
    ↓
ExecutionMode changes (LOCAL/CLOUD/HYBRID) take effect in HybridOrchestrator
    ↓
PrivacyLevel changes propagate to ContextEngine (what context is included in prompts)
    ↓
PreferredProvider changes affect RemoteModelRegistry provider selection order
    ↓
SETTINGS_AI_MODELS: zero dead routes remain for this screen
```

### Ripple Effect
**1 file**, ~5 lines. Zero other files affected.

### Testing Strategy
```
1. Navigate: Settings → "AI Execution Settings" → AIModelsSettingsScreen opens
2. Change ExecutionMode to CLOUD → verify persisted in airi_exec_prefs_secure (SharedPreferences inspection)
3. Restart app → ExecutionMode remains CLOUD (persistence test)
4. Send message → HybridOrchestrator uses CLOUD path (integration test)
5. Change PrivacyLevel → send message → context level respected
```

### Definition of Done
- [ ] `AiriRoute.SETTINGS_AI_MODELS` has ≥ 1 `navigate()` caller
- [ ] ExecutionMode, PrivacyLevel, InternetPermission, OfflineFallback, PreferredProvider accessible
- [ ] Settings persist in `airi_exec_prefs_secure` after change
- [ ] `ChatViewModel` uses updated ExecMode on next `sendMessage()` call

---

## AP-08 — INTEGRATIONSVIEWMODEL CREDENTIAL SPLIT-BRAIN

### Current State
**Status:** Legacy still used — Split-Brain. 3 separate credential namespaces. UI configuration non-functional.

**Why Not Active:**
`IntegrationsViewModel` writes GitHub credentials to `GithubService` namespace (key: `"github_pat_legacy"`). `GitHubConnector.connect()` reads from `ConnectorAuthManager` namespace (key pattern: `getCredential("github", "pat")`). These are completely different storage keys. Configuring GitHub via the UI stores credentials that the connector can never find. The same split-brain exists for Telegram and Notion. All three connectors appear to accept credentials but silently fail on every use.

**Dependency:** AP-04 must complete first.

### Activation Path
```
AP-04 completes (single SecureStorage instance)
    ↓
Audit ConnectorAuthManager key names for GitHub, Telegram, Notion, Google
    ↓
IntegrationsViewModel.kt — update all 3 credential WRITE calls to use ConnectorAuthManager
    ↓
IntegrationsViewModel.kt — add one-time migration (read old namespace → write new → clear old)
    ↓
Route Google credentials to ConnectorAuthManager "google" namespace (required for AP-10)
    ↓
Integration test: configure GitHub via IntegrationsScreen → invoke GitHub skill → success
    ↓
ConnectorsScreen shows CONNECTED status after IntegrationsScreen configuration
    ↓
(Post-validation) Delete GithubService, TelegramService credential write paths
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/viewmodel/IntegrationsViewModel.kt` | Update GitHub, Telegram, Notion write calls to `ConnectorAuthManager`; add migration; route Google to `ConnectorAuthManager` |

### Exact Methods to Modify
```kotlin
// BEFORE (GitHub):
githubService.storePat(pat)
// → SecureStorage.store("github_pat_legacy", pat)

// AFTER (GitHub):
ConnectorAuthManager.storeCredential("github", "pat", pat)
// → Same key that GitHubConnector.connect() reads

// BEFORE (Telegram):
telegramService.storeToken(token)
// → SecureStorage.store("telegram_bot_token_legacy", token)

// AFTER (Telegram):
ConnectorAuthManager.storeCredential("telegram", "bot_token", token)

// BEFORE (Notion):
// some Notion service call
// AFTER:
ConnectorAuthManager.storeCredential("notion", "integration_token", token)

// Google — add (required for AP-10):
ConnectorAuthManager.storeCredential("google", "oauth_token", googleAuthService.getToken())

// One-time migration (run in IntegrationsViewModel.init or first launch):
if (legacyGithubService.hasPat()) {
    ConnectorAuthManager.storeCredential("github", "pat", legacyGithubService.getPat())
    legacyGithubService.clearPat()
}
// Same pattern for Telegram and Notion legacy namespaces
```

### Dependency Activation Graph
```
AP-08 Activated
    ↓
GitHubConnector.connect() finds credentials → GitHub skills activate
    ↓
TelegramConnector.connect() finds credentials → Telegram messenger skill activates
    ↓
NotionMcpConnector.connect() finds credentials → Notion integration activates
    ↓
AP-10: GoogleConnector can find credentials in "google" namespace
    ↓
AP-06: Legacy GithubIntegration, TelegramIntegration, NotionIntegration safely deleted
    ↓
All 3 connector UI flows are now end-to-end functional (configure → use)
```

### Ripple Effect
**1 file** modified (`IntegrationsViewModel.kt`). The credential migration touches `SecureStorage` keys internally but no other source files change. After validation, `GithubService`, `TelegramService` credential methods may become dead — then delete those methods.

### Testing Strategy
```
Integration tests:
1. Configure GitHub via IntegrationsScreen (enter PAT) → invoke GithubGuardianSkill → succeeds
2. ConnectorsScreen shows GitHub as CONNECTED after IntegrationsScreen flow
3. Configure Telegram → invoke TelegramMessengerSkill → message sent
4. Configure Notion → invoke Notion connector action → success
5. One-time migration: existing legacy credentials in old namespace → auto-migrated to new namespace on first launch

Regression:
6. IntegrationsScreen → GitHub configuration UI still works (no UI changes required)
7. Previously configured integrations still work after migration (no credential loss)
```

### Definition of Done
- [ ] `ConnectorAuthManager.storeCredential()` is the only write path for all integration credentials
- [ ] `GitHubConnector.connect()` finds credentials written by `IntegrationsViewModel`
- [ ] Legacy namespace migration runs once on first launch after this change
- [ ] `ConnectorsScreen` shows CONNECTED for GitHub after IntegrationsScreen configuration
- [ ] Same for Telegram and Notion

---

## AP-09 — SHELL ENCODING GOVERNANCE BYPASS

### Current State
**Status:** Partially Active. Surface-form regex validation bypassed by encoding.

**Why Not Active:**
`PermissionGovernanceLayer` validates the literal string of shell commands against a regex blocklist. `$(base64 -d <<< 'cm0gLXJm')` is not matched by any pattern (the regex sees the encoded form). Decoded, it becomes `rm -rf`. Additionally, `SandboxExecutor.ALLOWED_SHELL` validates only the binary name (first token) — arguments are unrestricted. `find /data -name "*.db"` passes because `find` is whitelisted, even though the `/data` argument accesses sensitive paths.

### Activation Path
```
security/PermissionGovernanceLayer.kt — implement decodeAndExpand()
    ↓
Apply decodeAndExpand() before all regex validation in evaluate()
    ↓
agent/workspace/SandboxExecutor.kt — add BINARY_ARG_RESTRICTIONS map
    ↓
Apply argument validation in executeCommand() for whitelisted binaries
    ↓
Security tests: encoded rm -rf BLOCKED; path traversal BLOCKED; safe commands ALLOWED
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `security/PermissionGovernanceLayer.kt` | Add `decodeAndExpand()`; apply before regex validation |
| `agent/workspace/SandboxExecutor.kt` | Add `BINARY_ARG_RESTRICTIONS`; apply in `executeCommand()` |

### Exact Methods to Modify / Add

**`PermissionGovernanceLayer.kt`** — add:
```kotlin
private fun decodeAndExpand(command: String): String {
    // Decode base64 subshells: $(base64 -d <<< 'PAYLOAD')
    val base64Pattern = Regex("""\$\(base64\s+-d\s+<<<\s+'([^']+)'\)""")
    var expanded = base64Pattern.replace(command) { match ->
        try {
            Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            match.value // keep original if not valid base64
        }
    }
    // Decode hex subshells: $(echo 'HEX' | xxd -r -p)
    val hexPattern = Regex("""\$\(echo\s+'([0-9a-fA-F]+)'\s*\|\s*xxd\s+-r\s+-p\)""")
    expanded = hexPattern.replace(expanded) { match ->
        try {
            match.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        } catch (e: Exception) { match.value }
    }
    return expanded
}

// In evaluate(toolName: String, command: String):
val decoded = decodeAndExpand(command)
val blocked = dangerousPatterns.any { it.matches(decoded) }
if (blocked) return GovernanceDecision.BLOCK("Dangerous pattern detected after decoding: $toolName")
```

**`SandboxExecutor.kt`** — add:
```kotlin
private val BINARY_ARG_RESTRICTIONS = mapOf(
    "find" to Regex("""^\./.*|^\.$"""),      // relative paths only (./something or .)
    "ls"   to Regex("""^(\./.*|\.)?$"""),    // relative paths only or empty
    "cat"  to Regex("""^\./[^/].*"""),       // must start with ./
    "grep" to Regex("""^[^/].*"""),          // no absolute paths
)

// In executeCommand(binary: String, args: String):
val restriction = BINARY_ARG_RESTRICTIONS[binary]
if (restriction != null && !restriction.matches(args.trim())) {
    throw SecurityException("Argument scope violation: $binary $args (absolute or sensitive path)")
}
```

### Dependency Activation Graph
```
AP-09 Activated
    ↓
Shell governance layer is encoding-bypass-resistant
    ↓
Agent execution path (AgentLoop → ToolDispatcher → SandboxExecutor) is hardened
    ↓
FULL_AGENT mode (AP-05 prerequisite satisfied for secure deployment)
    ↓
Security posture: +5 points
```

### Ripple Effect
**2 files** modified. No new classes. No DI changes. No UI changes.

### Testing Strategy
```
Security tests (automated, run in CI):
1. evaluate("terminal", "$(base64 -d <<< 'cm0gLXJm')") → BLOCK ("rm -rf" detected after decode)
2. evaluate("terminal", "$(base64 -d <<< 'c3VkbyBybSAtcmYgLw==')") → BLOCK ("sudo rm -rf /" after decode)
3. evaluate("terminal", "find /data -name '*.db'") → BLOCK (absolute path violation)
4. evaluate("terminal", "find . -name '*.txt'") → ALLOW
5. evaluate("terminal", "ls ./") → ALLOW
6. evaluate("terminal", "cat ./output.txt") → ALLOW
7. evaluate("terminal", "ls /system") → BLOCK (absolute path)
8. evaluate("terminal", "cat /etc/passwd") → BLOCK (absolute path)

Regression:
9. Normal agent tool calls not affected — existing allowed patterns still pass
```

### Rollback Strategy
Remove `decodeAndExpand()` call and `BINARY_ARG_RESTRICTIONS` map. No data changes.

### Definition of Done
- [ ] `decodeAndExpand()` implemented and applied before all regex validation
- [ ] `BINARY_ARG_RESTRICTIONS` implemented and applied in `executeCommand()`
- [ ] All 8 security test cases pass (4 BLOCK, 4 ALLOW as documented above)
- [ ] No regression in normal agent tool execution

---

## AP-10 — GOOGLE CONNECTOR CREATION AND REGISTRATION

### Current State
**Status:** Architecture Only. GoogleAuthService exists. GoogleConnector does not exist. 3 skills crash at runtime.

**Why Not Active:**
`ConnectorBootstrap.installDefaults()` registers 13 connectors. `"google"` is not among them. `GmailAssistantSkill`, `CalendarEventsSkill`, and `DriveSearchSkill` all call `ToolExecutor.route("google", action, args)` → `ConnectorRegistry.find("google")` → null → `ConnectorNotFoundException` thrown at runtime. Three official skills listed as available crash on first invocation.

**Dependency:** AP-08 must complete first (Google credentials must be in `ConnectorAuthManager` "google" namespace).

### Activation Path
```
AP-08 completes (Google credentials in ConnectorAuthManager "google" namespace)
    ↓
CREATE connector/GoogleConnector.kt — implements Connector interface, wraps GoogleAuthService
    ↓
Implement: gmail_list, gmail_read, gmail_send, calendar_list, calendar_create, drive_search
    ↓
ServiceLocator.kt — wire googleAuthService as singleton (if not already present)
    ↓
connector/ConnectorBootstrap.kt — register GoogleConnector as connector #14
    ↓
GmailAssistantSkill, CalendarEventsSkill, DriveSearchSkill — now resolve without crash
    ↓
ConnectorHealthMonitor — includes Google in 60s health pings
    ↓
Integration tests: all 3 skills succeed for authenticated users; AuthRequired (not crash) for unauthenticated
    ↓
Production Ready
```

### Exact Files to Modify / Create
| File | Action |
|:---|:---|
| `connector/GoogleConnector.kt` | CREATE — ~120 lines |
| `connector/ConnectorBootstrap.kt` | Add `registry.register(GoogleConnector(...))` |
| `ServiceLocator.kt` | Add `googleAuthService` singleton if not already present |

### Full `GoogleConnector.kt` Structure
```kotlin
class GoogleConnector(
    private val googleAuthService: GoogleAuthService
) : Connector {
    override val id = "google"
    override val name = "Google"
    override val type = ConnectorType.API

    override suspend fun connect(): ConnectorResult =
        if (googleAuthService.isSignedIn()) ConnectorResult.Success
        else ConnectorResult.AuthRequired("Sign in with Google in Integrations settings")

    override suspend fun execute(action: String, args: Map<String, Any>): ConnectorResult =
        when (action) {
            "gmail_list"      -> executeGmailList(args)
            "gmail_read"      -> executeGmailRead(args)
            "gmail_send"      -> executeGmailSend(args)
            "calendar_list"   -> executeCalendarList(args)
            "calendar_create" -> executeCalendarCreate(args)
            "drive_search"    -> executeDriveSearch(args)
            else -> ConnectorResult.Failure("Unknown Google action: $action")
        }

    override suspend fun ping(): Boolean = try {
        googleAuthService.isSignedIn()
    } catch (e: Exception) { false }

    private suspend fun executeGmailList(args: Map<String, Any>): ConnectorResult {
        val token = googleAuthService.getAccessToken() ?: return ConnectorResult.AuthRequired("Re-sign in with Google")
        val maxResults = args["max_results"] as? Int ?: 10
        // Call Gmail REST API: GET https://gmail.googleapis.com/gmail/v1/users/me/messages
        // Authorization: Bearer $token
        // Return ConnectorResult.Success with message list
        TODO("Implement Gmail REST API call using token")
    }

    // Implement executeGmailRead, executeGmailSend, executeCalendarList,
    // executeCalendarCreate, executeDriveSearch following same pattern
}
```

### Exact Changes in `ConnectorBootstrap.kt`
```kotlin
// After the last existing registry.register() call (connector #13):
registry.register(GoogleConnector(ServiceLocator.googleAuthService))
```

### Dependency Activation Graph
```
AP-10 Activated (GoogleConnector registered as #14)
    ↓
GmailAssistantSkill.execute() → ToolExecutor.route("google", "gmail_list", ...) → GoogleConnector → success
    ↓
CalendarEventsSkill now functional
    ↓
DriveSearchSkill now functional
    ↓
All 14 official skills active (11 were working; 3 were broken at runtime)
    ↓
ConnectorHealthMonitor now includes Google in health dashboard
    ↓
Feature completeness: +3 skills activated
```

### Ripple Effect
**3 files**: 1 created (`GoogleConnector.kt`), 2 modified (`ConnectorBootstrap.kt`, `ServiceLocator.kt`).

### Testing Strategy
```
Integration tests:
1. User signed in with Google → invoke GmailAssistantSkill → gmail_list returns messages
2. User NOT signed in → invoke GmailAssistantSkill → ConnectorResult.AuthRequired (not crash)
3. Invoke CalendarEventsSkill with signed-in user → events listed
4. Invoke DriveSearchSkill with signed-in user → files returned
5. ConnectorHealthMonitor: Google connector included in 60s ping cycle

Unit tests:
6. GoogleConnector.connect() when isSignedIn() = true → ConnectorResult.Success
7. GoogleConnector.connect() when isSignedIn() = false → ConnectorResult.AuthRequired
8. GoogleConnector.execute("unknown_action", {}) → ConnectorResult.Failure("Unknown Google action")
9. GoogleConnector.ping() when API throws → returns false (no crash)
```

### Definition of Done
- [ ] `GoogleConnector.kt` created and registered as connector #14
- [ ] All 6 Google actions implemented (gmail_list, gmail_read, gmail_send, calendar_list, calendar_create, drive_search)
- [ ] GmailAssistantSkill integration test passes for authenticated user
- [ ] CalendarEventsSkill integration test passes
- [ ] DriveSearchSkill integration test passes
- [ ] Unauthenticated user → AuthRequired (not crash)
- [ ] ConnectorHealthMonitor includes Google

---

## AP-11 — SANDBOX TIME-BASED REAPER + AP-16/A-40 SCHEDULED JOBS

*(These three items share the same scheduler sprint and are activated together.)*
> **Wave note:** The Blueprint places A-16/A-40 in Wave 2. They are grouped here in Part 1 because they share the same scheduler wiring sprint as AP-11, are 5–10 lines each, and create no dependency conflicts with Part 2 items. Activating them early bounds the audit log and context cache tables before they can grow uncontrolled during Wave 2 feature work.

### Current State
- `WorkspaceRegistry.pruneStale()` — only called from `AIRIApplication.onTrimMemory()`. Memory pressure never guaranteed on high-RAM devices. **Status: Partially Active.**
- `AuditLogDao` — no `pruneOlderThan()` method. Table grows unboundedly. **Status: Architecture Only.**
- `ContextCacheDao.pruneExpired()` — called lazily on access only; stale entries never proactively cleaned. **Status: Partially Active.**

### Activation Path
```
memory/dao/AuditLogDao.kt — add pruneOlderThan(@Query)
    ↓
memory/repository/AuditRepository.kt — expose pruneOlderThan()
    ↓
ServiceLocator.kt — register 3 scheduled jobs in ScheduledJobOrchestrator:
    1. sandbox_reaper (every 30 min) → workspaceRegistry.pruneStale()
    2. audit_log_pruner (every 24h) → auditRepository.pruneOlderThan(30d cutoff)
    3. context_cache_pruner (every 24h) → contextCacheDao.pruneExpired(now)
    ↓
Testing: mock time → verify each pruner fires and removes correct rows
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `memory/dao/AuditLogDao.kt` | Add `@Query("DELETE FROM audit_log WHERE timestamp < :cutoffMs") suspend fun pruneOlderThan(cutoffMs: Long)` |
| `memory/repository/AuditRepository.kt` | Add `suspend fun pruneOlderThan(cutoffMs: Long) = auditLogDao.pruneOlderThan(cutoffMs)` |
| `ServiceLocator.kt` | Register 3 scheduled jobs after `workspaceRegistry` and `scheduledJobOrchestrator` init |

### Exact ServiceLocator Changes
```kotlin
// In ServiceLocator.init(), after workspaceRegistry and scheduledJobOrchestrator are created:

// 1. Sandbox reaper (every 30 minutes)
scheduledJobOrchestrator.scheduleRepeating(
    id = "sandbox_reaper",
    intervalMinutes = 30,
    task = ScheduledTask(
        description = "Prune stale sandbox workspaces",
        action = { workspaceRegistry.pruneStale() }
    )
)

// 2. Audit log pruner (every 24 hours, 30-day retention)
scheduledJobOrchestrator.scheduleRepeating(
    id = "audit_log_pruner",
    intervalMinutes = 24 * 60,
    task = ScheduledTask(
        description = "Prune audit log entries older than 30 days",
        action = {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            auditRepository.pruneOlderThan(cutoff)
        }
    )
)

// 3. Context cache pruner (every 24 hours)
scheduledJobOrchestrator.scheduleRepeating(
    id = "context_cache_pruner",
    intervalMinutes = 24 * 60,
    task = ScheduledTask(
        description = "Prune expired context cache entries",
        action = { contextCacheDao.pruneExpired(System.currentTimeMillis()) }
    )
)
```

### Dependency Activation Graph
```
AP-11/AP-16/A-40 Activated (3 scheduled jobs)
    ↓
Sandbox workspace memory leak eliminated (high-RAM device path)
    ↓
Audit log bounded to 30-day rolling window (DB size controlled)
    ↓
Context cache stale entries proactively removed (not just on-demand)
    ↓
AP-02 (database encryption) benefits: smaller encrypted DB = faster migration
```

### Ripple Effect
**3 files** modified. No new classes (assuming `ScheduledTask` class already exists per the inventory).

### Testing Strategy
```
Unit tests:
1. Sandbox: create workspace → advance mock time 31 min → pruneStale() called → workspace gone
2. Audit: insert rows with timestamps older than 30d → trigger pruner → rows deleted; recent rows retained
3. Context cache: insert expired entries → trigger pruner → expired rows deleted; valid rows retained

Integration:
4. App runs for simulated 24h → audit_log_pruner fires → table size bounded
```

### Definition of Done
- [ ] `AuditLogDao.pruneOlderThan()` implemented
- [ ] `AuditRepository.pruneOlderThan()` delegates to DAO
- [ ] All 3 scheduled jobs registered in ServiceLocator
- [ ] Sandbox reaper fires every 30 min regardless of memory pressure
- [ ] Audit log pruner fires every 24h (30-day retention)
- [ ] Context cache pruner fires every 24h

---

## AP-47 — DEBUG OVERLAY PRODUCTION GATE

### Current State
**Status:** Partially Active. Gate status unknown — may be leaking internal performance data to all production users.

**Why Not Active (for production gate):**
`DebugOverlay.kt` shows inference latency, tokens/sec, and heap stats in `ChatScreen`. The gate condition (`BuildConfig.DEBUG` or `debugMode`) has not been confirmed correct. If unprotected, all production users see internal performance metrics.

### Activation Path
```
Open ui/debug/DebugOverlay.kt — inspect the visibility condition
    ↓
IF already correct (BuildConfig.DEBUG || agentViewModel.debugMode.value):
    → Document and close
IF incorrect (always visible or missing gate):
    → Apply: if (BuildConfig.DEBUG || agentViewModel.debugMode.value) { DebugOverlay(...) }
    ↓
Production build test: DebugOverlay NOT visible
Debug build test: DebugOverlay visible
AgentControlScreen debugMode toggle test: overlay appears/disappears
    ↓
Production Ready
```

### Exact Files to Modify
| File | Change |
|:---|:---|
| `ui/debug/DebugOverlay.kt` | Verify/enforce production gate |
| `ui/ChatScreen.kt` | Wrap DebugOverlay call in gate condition (if not already) |

### Exact Gate Condition
```kotlin
// In ChatScreen.kt, wherever DebugOverlay is called:
val isDebugMode by agentViewModel.debugMode.collectAsState()
if (BuildConfig.DEBUG || isDebugMode) {
    DebugOverlay(/* existing params */)
}
```

### Testing Strategy
```
1. Release build (BuildConfig.DEBUG = false) + debugMode = false → overlay NOT visible
2. Release build + debugMode = true (via AgentControlScreen) → overlay visible
3. Debug build → overlay always visible (regardless of debugMode)
```

### Definition of Done
- [ ] Production builds (`BuildConfig.DEBUG = false`, `debugMode = false`) show no overlay
- [ ] Developer with `debugMode = true` sees overlay in production build
- [ ] Debug builds show overlay always

---

*AIRI Activation Plan — Part 1 complete.*
*Items covered: AP-01 through AP-11, AP-16, AP-40, AP-47 (Wave 1 full + scheduler sprint)*
*All Part 1 items are prerequisite for or independent of Part 2 items.*
