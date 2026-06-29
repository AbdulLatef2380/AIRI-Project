# AIRI Phase 0 Architecture Integrity Audit — Tasks 3–9
**Date:** 2026-06-29 | **Status:** COMPLETE | **Auditor:** AIRI Engineering Agent

---

## Scope
Static code audit of Tasks 3–9 as delivered in Phase 2 of the AIRI Engineering Blueprint. All files were read directly from the live Kotlin source tree under `app/src/main/java/com/airi/assistant/`. No build or runtime instrumentation was performed.

---

## Audit Results by Task

### Task 2 — ExecModePreferences Encryption Migration
**Verdict: ✅ PASS**

- `ExecModePreferences` uses `EncryptedSharedPreferences` (AES256-SIV keys / AES256-GCM values) backed by Android Keystore (`MasterKey.AES256_GCM`).
- In-memory fallback (`ExecModeInMemoryPreferences`) activates on broken Keystore — no plaintext disk writes in the fallback path.
- Legacy `airi_exec_prefs` plaintext file is migrated key-by-key on first launch; each key is read back and verified before the source file is cleared and deleted. Migration is idempotent and guarded by try/catch.
- `isEncrypted: Boolean` is publicly exposed for UI/Settings warning.
- **AIRI_PROOF tags present:** `EXEC_PREFS_ENCRYPT_FAILED`, `EXEC_PREFS_MIGRATION_START`, `EXEC_PREFS_MIGRATION_VERIFIED`, `EXEC_PREFS_MIGRATION_COMPLETE`, `EXEC_PREFS_MIGRATION_PARTIAL`, `EXEC_PREFS_MIGRATION_ERROR`.

**No issues found.**

---

### Task 3 — AuthService Enforcement (UI FirebaseAuth Bypass)
**Verdict: ✅ PASS (with documented infrastructure caveats)**

- `LoginScreen` exclusively calls `ServiceLocator.authService` — no direct `FirebaseAuth.getInstance()` usage in any UI composable.
- `AuthService` is a canonical facade covering email/password, Google OAuth, GitHub OAuth, sign-out, and GDPR deletion.
- All auth events emit `AppEvent` to `EventBus` and log `AIRI_PROOF` lines.
- `SessionManager` and `CloudSyncCoordinator` hold `FirebaseAuth.getInstance()` — both are **infrastructure classes, not UI layers**, which is architecturally acceptable per the blueprint. These represent vendor lock-in risk, not a Task 3 violation.

**Infrastructure caveat (non-blocking):** `SessionManager.auth` and `CloudSyncCoordinator.auth` are direct Firebase references. These should be routed through `AuthService` in a future hardening pass, but are outside the Task 3 scope.

---

### Task 4 — GDPR Account Deletion
**Verdict: ✅ PASS**

- `AuthService.deleteAccount()` implemented with correct sequencing:
  1. Force-refresh ID token (`getIdToken(true)`) to validate session freshness.
  2. Call `user.delete()` which revokes server-side refresh tokens.
  3. Call `firebaseAuth.signOut()` to clear local credentials.
  4. Emit `AppEvent.UserSignedOut()` to notify all subscribers.
- UI entry point: `PrivacyDataSettingsScreen` (line 159) presents a destructive confirmation dialog before invoking `deleteAccount`.
- `AIRI_PROOF` tags: `AUTH_DELETE_ACCOUNT_INITIATED`, `AUTH_DELETE_ACCOUNT_SUCCESS`, `AUTH_DELETE_ACCOUNT_FAILED`, `AUTH_DELETE_ACCOUNT_TOKEN_REFRESH_FAILED`.
- Re-authentication requirement is documented in KDoc; `FirebaseAuthRecentLoginRequiredException` will surface to the UI via the `onComplete(false, errorMsg)` callback.

**No issues found.**

---

### Task 5 — Persistent AuditLog (Room)
**Verdict: ✅ PASS — Infrastructure complete; integration partial**

**What is correct:**
- `AuditLogEntity` — properly defined with `@Entity(tableName = "audit_log", indices = [Index("timestampMs"), Index("tag")])`.
- `AuditLogDao` — CRUD complete; `observeRecent()` returns `Flow<List<AuditLogEntity>>` for reactive UI.
- `AuditRepository` — fire-and-forget via `SupervisorJob + Dispatchers.IO`; retention pruning every 500 writes.
- `AuditLogTypeConverters` — `Level` enum serialized to/from String.
- `AiriDatabase` v4 — migration `MIGRATION_3_4` adds `audit_log` table with both indices. All 8 entities registered.
- `ServiceLocator.auditRepository` — registered as a lazy singleton.

**Gap identified (addressed in Task 10):**
`AuditRepository.log()` is only called from the `systemHealthCoordinator` thermal callback in `ServiceLocator`. Auth events (`signIn`, `signOut`, `deleteAccount`), firewall decisions (`allows()`/`guard()`), and skill execution events all write to logcat only (`Log.i(TAG, "AIRI_PROOF ...")`). These must also write to `AuditRepository` so the persistent audit trail is complete.

---

### Task 6 — PreferenceCoordinator
**Verdict: ✅ PASS — Facade complete; adoption partial**

- `PreferenceCoordinator` wraps `ExecModePreferences`, `VoicePreferencesStore`, and theme `SharedPreferences`.
- `resetAllToDefaults()` covers all three stores.
- Theme preferences intentionally remain in plaintext `SharedPreferences` (not privacy-critical per blueprint).
- Registered in `ServiceLocator.preferenceCoordinator` as a lazy singleton.

**Gap identified (addressed in Task 12):**
`ExecModePreferences` is directly constructed in `ChatViewModel` (line 327), `ModelLibraryScreen` (line 76), `CommandRouter` (line 240), and `UnifiedCognitiveLoop` (line 527). These bypass the singleton `PreferenceCoordinator`, creating split-brain risk when preferences change. The `ChatViewModel` path (the most critical) is fixed in Task 12; remaining callers are tracked for a follow-up pass.

---

### Task 7 — ArtifactPreviewScreen (Sandboxed WebView)
**Verdict: ✅ PASS**

WebView hardening verified:
- JavaScript: **disabled** (`settings.javaScriptEnabled = false`)
- File access: **disabled** (`settings.allowFileAccess = false`, `settings.allowContentAccess = false`)
- URL navigation: **blocked** (`shouldOverrideUrlLoading` returns `true`, logs AIRI_PROOF)
- CSP meta tag: **injected** into all rendered HTML
- Content types: Markdown and code rendered natively (no execution risk)
- Route: correctly registered at `${AiriRoute.ARTIFACT_PREVIEW}/{type}/{content}` in `AiriApp.kt` (line 619)
- Deep-link helper `AiriRoute.artifactPreview(type, content)` URL-encodes the content; NavHost URL-decodes it on receipt.

**Note (low severity):** `loadDataWithBaseURL(null, ...)` — a null base URL causes WebView to treat the origin as `about:blank`, which is correct for sandbox isolation. CSP enforcement via the meta tag is the defense in depth here.

---

### Task 8 — NotionMcpConnector
**Verdict: ✅ PASS**

- Full HTTP client over Notion REST API v1 using OkHttp.
- Token stored exclusively in `SecureStorage` (Android Keystore-backed); never written to logcat or memory outside the request.
- Tools implemented: `search_pages`, `get_page`, `get_page_blocks`, `create_page`, `query_database`.
- Handshake (`/v1/users/me`) verifies token validity before accepting the connector.
- Registered in `ConnectorBootstrap` with `SecureStorage` injection.
- Legacy `NotionIntegration` fallback preserved in `ConnectorBootstrap` for broken Keystore edge case (acceptable safety net).
- `TAG = "AIRI_NotionMcpConnector"` — AIRI_PROOF logging present.

**No issues found.**

---

### Task 9 — SystemHealthCoordinator
**Verdict: ⚠️ PARTIAL — Observation loop wired; feedback loop not closed**

**What is correct:**
- `SystemHealthCoordinator` subscribes to `ThermalProfiler.throttleLevel` (a `StateFlow`) via `distinctUntilChanged`.
- `ThrottleAction` sealed class correctly maps levels: `NONE → FullPerformance`, `REDUCE → ReduceLoad(0.5f)`, `EMERGENCY → EmergencyStop`.
- `contextBudgetFactor: Float` computed property exposes the current factor (1.0 / 0.5 / 0.0).
- `isEmergency: Boolean` exposes the emergency state without subscribing.
- `start()` / `stop()` lifecycle is correct. Registered in `ServiceLocator` and started via `.also { it.start() }`.

**Critical gap (addressed in Task 11):**
The `onThrottleChange` callback in `ServiceLocator` (lines 371–377) only writes to `AuditRepository`. It does **not** update any downstream token budget. `ContextBudget` and `PromptBudgetLedger` are unaware of thermal state. The `ReduceLoad(0.5f)` action is emitted but never applied to the context window. The feedback loop is open.

**Fix:** `ThermalSignal` singleton (Task 11) is set by `ServiceLocator.onThrottleChange`; `PromptBudgetLedger.forBudget()` applies `ThermalSignal.contextBudgetFactor` as a pre-allocation that shrinks the available budget for all contributors.

---

## Security Findings

### S-1: ExecutionFirewall fail-open — RESOLVED (pre-existing fix)
**Verdict: ✅ CONFIRMED FIXED**

`ExecutionFirewall.allows()` returns `false` for unknown tools (line 103: `?: return false`). `guard()` throws `UnknownToolException`. No `getOrElse { true }` anywhere in the codebase. The P0 regression is verified resolved.

### S-2: Room `exportSchema = false`
**Verdict: ⚠️ LOW RISK (pre-existing)**

`@Database(exportSchema = false)` prevents Room from generating migration validation scripts. Acceptable for the current development stage; should be set to `true` and the schema directory committed before Play Store release.

### S-3: Firebase init before GDPR consent
**Verdict: ⚠️ MEDIUM RISK (addressed in Task 14)**

`FirebaseApp.initializeApp()` is called automatically by Firebase's `FirebaseInitProvider` ContentProvider at process start, before any consent is checked. `AnalyticsService.init()` correctly gates collection behind `TelemetryConsentStore`, and `FirebaseCrashReporter.enableCollection()` is called conditionally. However the SDK itself initializes unconditionally. Fix: add `firebase_analytics_collection_deferred=true` and `firebase_crashlytics_collection_enabled=false` to `AndroidManifest.xml` meta-data; enable programmatically after consent.

### S-4: ReferralManager plaintext SharedPreferences — HIGH RISK (addressed in Task 13)
**Verdict: ⚠️ HIGH — rooted-device bonus fraud vector**

`ReferralManager` stores `bonus_messages`, `share_bonus_granted`, and referral codes in plaintext `airi_referrals` SharedPreferences. On a rooted device, any process can read and modify these values, granting arbitrary bonus messages or replaying referral codes. Fix: migrate to `EncryptedSharedPreferences` with plaintext migration, matching the pattern in `ExecModePreferences`.

---

## AuditRepository Integration Gap Map

The following AIRI_PROOF events currently write to **logcat only** and must also write to `AuditRepository`:

| Module | Events |
|:-------|:-------|
| `AuthService` | `AUTH_SIGN_IN`, `AUTH_SIGN_IN_FAILED`, `AUTH_SIGN_OUT`, `AUTH_DELETE_ACCOUNT_*` |
| `ExecutionFirewall` | `FIREWALL_DENY`, `FIREWALL_ALLOW` (high-frequency — use sampling) |
| `SkillAuditLogger` | All skill invocation/result events |
| `ExecModePreferences` | `EXEC_PREFS_MIGRATE_*` |
| `SystemHealthCoordinator` | Already wired ✅ |
| `NotionMcpConnector` | Tool calls (optional — data volume risk) |

---

## Blueprint Gap Summary

| # | Gap | Severity | Task |
|:--|:----|:---------|:-----|
| G-1 | AuditRepository not wired to Auth/Firewall events | High | Task 10 |
| G-2 | SystemHealthCoordinator → ContextBudget feedback loop open | High | Task 11 |
| G-3 | ExecModePreferences bypassed by ChatViewModel/CommandRouter | Medium | Task 12 |
| G-4 | ReferralManager in plaintext SharedPreferences | High | Task 13 |
| G-5 | Firebase SDK auto-inits before GDPR consent | Medium | Task 14 |
| G-6 | Landing page missing SEO meta/sitemap/robots.txt | Low | Task 15 |

---

## Sign-off

All Tasks 3–9 implementations are structurally sound. Security posture is materially improved over the pre-Phase-2 baseline. The six gaps above are tracked and addressed in Tasks 10–15.

*Generated by AIRI Engineering Agent — 2026-06-29*
