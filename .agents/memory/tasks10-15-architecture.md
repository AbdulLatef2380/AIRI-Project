---
name: Tasks 10–15 Architecture Decisions
description: Phase 2 Tasks 10–15 — audit wiring, thermal feedback loop, preference consolidation, referral security, consent gating, SEO.
---

## Task 10 — AuditRepository Full Integration
AuthService constructor now accepts `auditRepository: AuditRepository? = null`. All AIRI_PROOF events (signIn, signOut, createAccount, deleteAccount, all OAuth paths) call `auditRepository?.info/warn/error(...)` alongside the existing `Log.i`. ServiceLocator wires `AuthService(auditRepository = auditRepository)`. Default null keeps AuthService unit-testable without a DB.

**Why:** AuditRepository was written to Room but only the thermal callback in ServiceLocator called it. All auth security events were logcat-only (ephemeral).

## Task 11 — ThermalSignal + PromptBudgetLedger Integration
Created `ThermalSignal.kt` (`runtime/health/`) — a process-wide singleton with two AtomicReference fields: `contextBudgetFactor: Float` (1.0/0.5/0.0) and `isEmergency: Boolean`. `update()` is `internal` — only ServiceLocator calls it.

ServiceLocator.onThrottleChange now decodes the ThrottleAction to (factor, emergency) and calls `ThermalSignal.update()` before writing to auditRepository.

PromptBudgetLedger.forBudget() pre-allocates a `THERMAL_RESERVE` contributor (added to the Contributor enum, first/highest priority) = `nCtx * (1 - thermalFactor)` before any other claim. Under NONE throttle: reserve=0, no change. Under REDUCE: reserve=50% of nCtx, halves all downstream budgets.

**Why:** The feedback loop was open — ThermalProfiler collected real signals but ContextBudget was never reduced.

## Task 12 — PreferenceCoordinator as Single Source of Truth
Added `val rawExecPrefs: ExecModePreferences get() = execPrefs` to `PreferenceCoordinator` (exposes the backing instance without breaking encapsulation).

Added `val execModePrefs: ExecModePreferences get() = preferenceCoordinator.rawExecPrefs` to ServiceLocator — a computed property (not lazy) so it's always in sync with preferenceCoordinator.

Fixed ChatViewModel line 327: `private val execModePrefs = com.airi.assistant.core.ServiceLocator.execModePrefs`.

**Remaining:** CommandRouter, ModelLibraryScreen, UnifiedCognitiveLoop still construct ExecModePreferences directly — tracked for follow-up.

## Task 13 — ReferralManager EncryptedSharedPreferences Migration
ReferralManager now uses `EncryptedSharedPreferences` (`airi_referrals_secure`) with AES256_GCM MasterKey + AES256_SIV key encryption. On `init()`, `migrateIfNeeded()` reads from the legacy `airi_referrals` plaintext file, writes to the encrypted store, marks migration done via `MIGRATION_DONE_KEY`, then clears the legacy file. Idempotent. Fallback to plaintext on Keystore failure (logged as AIRI_PROOF warning).

**Why:** Bonus message count and referral codes in plaintext SharedPreferences = trivially inflatable on rooted devices.

## Task 14 — Deferred Analytics Consent Gate
`AIRIApplication.onCreate()` now guards `AnalyticsService.installOpen()` behind `ServiceLocator.telemetryConsentStore.current.analyticsEnabled`. On fresh install (no consent yet), the install-open event is NOT fired. Responsibility passes to OnboardingScreen to fire `installOpen()` + set `install_open_logged=true` after user grants consent.

**Note:** Firebase SDK itself auto-inits via `FirebaseInitProvider` ContentProvider regardless of consent. Full SDK deferral requires `firebase_analytics_collection_deferred=true` in AndroidManifest meta-data — not yet implemented (lower risk since collection is already gated at the AnalyticsService level).

## Task 15 — React Landing Page SEO
`index.html` — added: canonical link, OpenGraph tags (og:type/url/title/description/image/width/height/site_name/locale), Twitter Card tags (summary_large_image), Schema.org JSON-LD (SoftwareApplication). OG image URL: `https://airi.app/og-image.png` (placeholder — real asset needed).

`public/sitemap.xml` — created with `/` (weekly, priority 1.0) and `/referral` (monthly, 0.5).

`public/robots.txt` — created with `Allow: /` + Sitemap reference.

## Audit Report
Written to `docs/reports/phase0_audit_report.md`. All Tasks 3–9 pass or pass with documented caveats. Six gaps (G-1 through G-6) mapped to Tasks 10–15.
